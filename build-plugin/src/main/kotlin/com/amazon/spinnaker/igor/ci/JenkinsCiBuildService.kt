/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.amazon.spinnaker.igor.ci;

import com.netflix.spinnaker.igor.build.model.GenericArtifact
import com.netflix.spinnaker.igor.build.model.GenericBuild
import com.netflix.spinnaker.igor.build.model.GenericGitRevision
import com.netflix.spinnaker.igor.ci.CiBuildService
import com.netflix.spinnaker.igor.config.JenkinsConfig
import com.netflix.spinnaker.igor.config.JenkinsProperties
import com.netflix.spinnaker.igor.config.client.JenkinsOkHttpClientProvider
import com.netflix.spinnaker.igor.config.client.JenkinsRetrofitRequestInterceptorProvider
import com.netflix.spinnaker.igor.docker.model.DockerRegistryAccounts
import com.netflix.spinnaker.igor.docker.service.TaggedImage
import com.netflix.spinnaker.igor.jenkins.client.JenkinsClient
import com.netflix.spinnaker.igor.jenkins.client.model.Build
import com.netflix.spinnaker.igor.jenkins.client.model.ScmDetails
import com.netflix.spinnaker.igor.scm.AbstractScmMaster
import com.netflix.spinnaker.igor.scm.github.client.GitHubMaster
import com.netflix.spinnaker.igor.scm.github.client.model.Commit
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.security.AuthenticatedRequest
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import mu.KotlinLogging
import org.apache.commons.io.IOUtils
import org.springframework.web.util.UriUtils
import retrofit.RetrofitError
import retrofit.client.Response
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.function.Predicate
import java.util.regex.Pattern
import javax.net.ssl.*

class JenkinsCiBuildService(
    private val jenkinsProperties: JenkinsProperties,
    private val dockerRegistryAccounts: DockerRegistryAccounts,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val scmMasters: List<AbstractScmMaster>,
    private val jenkinsOkHttpClientProvider: JenkinsOkHttpClientProvider,
    private val jenkinsRetrofitRequestInterceptorProvider: JenkinsRetrofitRequestInterceptorProvider,
) : CiBuildService {

    private val logger = KotlinLogging.logger {}
    private var retrySupport : RetrySupport
    private var circuitBreaker : CircuitBreaker
    lateinit var jenkinsClient : JenkinsClient
    lateinit var scmMaster : GitHubMaster
    lateinit var jenkinsHostId : String

    init {
        configure()
        retrySupport = RetrySupport()
        circuitBreaker =
            circuitBreakerRegistry.circuitBreaker(
                "jenkins-" + jenkinsHostId,
                CircuitBreakerConfig.custom()
                    .ignoreException(Predicate<Throwable> { re ->
                        if (re is RetrofitError) {
                            return@Predicate re.getKind() == RetrofitError.Kind.HTTP && re.getResponse()
                                .getStatus() == 404
                        }
                        return@Predicate false
                    }).build()
            )
    }

  override fun getBuilds(
      projectKey: String?,
      repoSlug: String?,
      buildNumber: String?,
      commitId: String?,
      completionStatus: String?
  ) : List<GenericBuild>{
    // repoSlug is supposed to match the Jenkins job name
    var jobName = repoSlug
    val imageArtifacts = getImages()
    val genericBuilds = mutableListOf<GenericBuild>();
    buildNumber?.let { bn ->
      imageArtifacts.forEach { i ->
          i.artifact?.let {
              val labels = (it["metadata"] as Map<String, Map<String, String>>)["labels"] as Map<String, String>
              if (buildNumber == labels["buildNumber"] && commitId == labels["commitId"]) {
                  logger.info("found matching image with buildNumber {} and commitId {}", buildNumber, commitId)
                  labels["jobName"]?.let { name ->
                      jobName = name
                  }
                  logger.debug("labels: {}", labels)
                  return@forEach
              }
          }
      }

      jobName?.let {
          logger.info("getting git details with jobName {} and buildNumber {}", jobName, bn)
          return listOf(getGenericBuildWithGitDetails(jobName!!, Integer.parseInt(bn), imageArtifacts))
      }
      return genericBuilds
    }

    jobName?.let {
        callBuilds(it).stream().forEach{ b -> genericBuilds.add(
            getGenericBuildWithGitDetails(
                it,
                b.number,
                imageArtifacts
            )
        )}
    }
    return genericBuilds
  }

  override fun getBuildOutput(buildId: String) : Map<String, Any> {
    val p = Pattern.compile("(.+)-(.+)");
    val m = p.matcher(buildId);
    if (!m.find()) return emptyMap()

    try {
        callBuildOutput(m.group(1), m.group(2))?.let {
            val body = IOUtils.toString(it.getBody().`in`(), StandardCharsets.UTF_8)
            return mapOf("log" to body)
        }
    }catch(e: Exception) {
        logger.error(e.message)
    }

    return mapOf("log" to "")
  }

   fun getGenericBuildWithGitDetails(jobName: String, buildNumber: Int, images: List<TaggedImage>) : GenericBuild {

    val revs = getGitDetails(jobName, buildNumber)?.genericGitRevisions()?: emptyList<GenericGitRevision>();
    val properties = mutableMapOf<String, String>()
    val artifacts = mutableListOf<GenericArtifact>()
    val updatedRevs = mutableListOf<GenericGitRevision>()

    val p = Pattern.compile("^(\\w+://|\\w+@|)(.+?)(:|/)(.+?)/(.+)")
    revs.forEach { grv ->
      val m = p.matcher(grv.remoteUrl);
      if (m.find()) {
        val commit = try {
            this.scmMaster.getCommitDetails(m.group(4), m.group(5).removeSuffix(".git"), grv.getSha1()) as Commit
        } catch (e: Exception) {
            updatedRevs.add(grv)
            logger.error(e.message)
            return@forEach
        }
        properties.put("projectKey", m.group(4))
        properties.put("repoSlug", m.group(5).removeSuffix(".git"))
        updatedRevs.add(grv
                .withCommitter(commit.getAuthor().getName())
                .withMessage(commit.getMessage())
                .withTimestamp(commit.getAuthor().getDate())
                .withCompareUrl(commit.html_url)
        )

        // pull artifact details based on the buildNumber and commit sha1 in its metadata
        getArtifact(images, jobName, buildNumber, commit.getSha())?.let {
            artifacts.add(it)
        }
      }
    }
   return callBuild(jobName, buildNumber).genericBuild(jobName)
       .withGenericGitRevisions(updatedRevs).withProperties(properties).withArtifacts(artifacts);
  }


  private fun callBuilds(jobName: String) : List<Build> {
    return circuitBreaker.executeSupplier{
        AuthenticatedRequest.allowAnonymous{
            jenkinsClient.getBuilds(encode(jobName)).list
        }
    }
  }

  private fun callBuild(jobName: String, buildNumber: Int) : Build {
    return circuitBreaker.executeSupplier{jenkinsClient.getBuild(encode(jobName), buildNumber)};
  }

  private fun getGitDetails(jobName: String, buildNumber: Int) : ScmDetails? {
    return retrySupport.retry(
        {
            try {
                return@retry jenkinsClient.getGitDetails(encode(jobName), buildNumber)
            } catch (e: RetrofitError) {
                // assuming that a conversion error is unlikely to succeed on retry
                if (e.getKind() == RetrofitError.Kind.CONVERSION) {
                    return@retry null;
                } else {
                    throw e;
                }
            }
        },
        10,
        Duration.ofMillis(1000), false
    );
  }

  private fun getImages() : List<TaggedImage> {
    val images = mutableListOf<TaggedImage>()
    this.dockerRegistryAccounts.getAccounts().forEach { account ->
      val accountName : String = account.get("name") as String
      images.addAll(AuthenticatedRequest.allowAnonymous {
          dockerRegistryAccounts.service.getImagesByAccount(accountName, true)
      })
    }
    return images
  }

  private fun getArtifact(images: List<TaggedImage>, jobName: String, buildNumber: Int, commitId: String): GenericArtifact? {
    images.forEach { im ->
      if (im.buildNumber == buildNumber.toString() && im.commitId == commitId) {
        val url = String.format("https://%s/v2/%s/blobs/%s", im.registry, im.repository, im.digest)
        return@getArtifact GenericArtifact("docker", im.repository, im.tag, im.digest)
                .withUrl(url).withDisplayPath(url)
      }
    }

    return null
  }

  private fun callBuildOutput(jobName: String, buildNumber: String) : Response? =
    circuitBreaker.executeSupplier{jenkinsClient.getBuildOutput(encode(jobName), buildNumber)}

  private fun encode(uri: String) : String =
    UriUtils.encodeFragment(uri, "UTF-8");

  private fun configure() {
      scmMasters.forEach { m ->
          if (m is GitHubMaster) {
              scmMaster = m
              return@forEach
          }
      }

      jenkinsProperties.masters.forEach { host ->
          jenkinsHostId = host.name
          if (host.ciEnabled) {
              jenkinsClient = JenkinsConfig.jenkinsClient(
                  host,
                  jenkinsOkHttpClientProvider,
                  jenkinsRetrofitRequestInterceptorProvider,
              )
              return@forEach
          }
      }
  }
}
