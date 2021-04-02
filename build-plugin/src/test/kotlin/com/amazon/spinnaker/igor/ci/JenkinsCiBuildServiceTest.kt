package com.amazon.spinnaker.igor.ci

import com.netflix.spinnaker.igor.build.model.Result
import com.netflix.spinnaker.igor.config.JenkinsProperties
import com.netflix.spinnaker.igor.config.client.DefaultJenkinsOkHttpClientProvider
import com.netflix.spinnaker.igor.config.client.DefaultJenkinsRetrofitRequestInterceptorProvider
import com.netflix.spinnaker.igor.docker.model.DockerRegistryAccounts
import com.netflix.spinnaker.igor.docker.service.ClouddriverService
import com.netflix.spinnaker.igor.docker.service.TaggedImage
import com.netflix.spinnaker.igor.jenkins.client.JenkinsClient
import com.netflix.spinnaker.igor.jenkins.client.model.*
import com.netflix.spinnaker.igor.scm.github.client.GitHubMaster
import com.netflix.spinnaker.igor.scm.github.client.model.Author
import com.netflix.spinnaker.igor.scm.github.client.model.Commit
import com.netflix.spinnaker.igor.scm.github.client.model.CommitInfo
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.coEvery
import io.mockk.mockk
import okhttp3.OkHttpClient
import retrofit.client.Response
import retrofit.mime.TypedInput
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.*

@Suppress("UNCHECKED_CAST")
internal class JenkinsCiBuildServiceTest : JUnit5Minutests {

    private val props = JenkinsProperties()
    private val okHttpClient : OkHttpClient = mockk()
    private val mockScmMaster : GitHubMaster = mockk()
    private val defaultProvider = DefaultJenkinsOkHttpClientProvider(okHttpClient)
    private val defaultInterceptor = DefaultJenkinsRetrofitRequestInterceptorProvider()
    private val dockerAccounts = DockerRegistryAccounts()
    private val jenkinsClient = mockk<JenkinsClient>()
    private val clouddriverService = mockk<ClouddriverService>()
    private lateinit var buildService : JenkinsCiBuildService

    fun taggedImage(repo: String, buildNumber: String, commitSha: String, digest: String) : TaggedImage {
        val img = TaggedImage()
        img.account = "some-account"
        img.branch = "main"
        img.buildNumber = buildNumber
        img.commitId = commitSha
        img.digest = digest
        img.repository = repo
        return img
    }

    fun getBranch(name: String, sha: String): Branch {
        val br = Branch()
        br.name = name
        br.sha1 = sha
        return br
    }

    fun getRevision(b: Branch) : Revision{
        val r = Revision()
        r.branch = listOf(b)
        return r
    }

    fun getBuild(rev: Revision) : ScmBuild {
        val b = ScmBuild()
        b.revision = rev
        return b
    }

    fun getAction(url: String, b: ScmBuild) : Action {
        val a = Action()
        a.remoteUrl = url
        a.build = b
        return a
    }

    fun getScmDetails(a: Action?) : ScmDetails {
        val details = ScmDetails()
        details.actions = arrayListOf(a)
        return details
    }

    fun getCommitDetails() : Commit {
        val c = Commit()
        c.url = "some-url"
        c.sha = "some-sha"
        c.message = "some-message"
        c.html_url = "some-html-url"

        val a = Author()
        a.name = "some-name"
        a.email = "alias@example.com"

        val ci = CommitInfo()
        ci.message = "some-commit-msg"
        ci.author = a

        c.author = a
        return c
    }

    fun getBuildsResult() : Build {
        val b = Build()
        b.building = false
        b.number = 4
        b.result = "SUCCESS"
        b.timestamp = "1613503078265"
        b.duration = 37527
        b.url = "some-url"
        return b
    }

    fun getBuildList() : BuildsList {
        val bl = BuildsList()
        bl.list = listOf(getBuildsResult())
        return bl
    }

    fun getJenkinsHost() : JenkinsProperties.JenkinsHost {
        val h = JenkinsProperties.JenkinsHost()
        h.name    = "some-host"
        h.address = "some-address"
        return h
    }

    fun getResponse() : Response {
        val body = mockk<TypedInput>()
        coEvery { body.`in`() } returns "yolo".toByteArray(Charsets.UTF_8).inputStream()
        return Response("som-url", 200, "good", mutableListOf(), body)
    }

    fun tests() = rootContext {
        before {
            dockerAccounts.service = clouddriverService
            dockerAccounts.accounts.add(mapOf("name" to "something"))
            props.masters = listOf(getJenkinsHost())
            buildService = JenkinsCiBuildService(
                    props,
                    dockerAccounts,
                    CircuitBreakerRegistry.ofDefaults(),
                    listOf(mockScmMaster),
                    defaultProvider,
                    defaultInterceptor
            )
            buildService.jenkinsHostId = "some-host"
            buildService.jenkinsClient = jenkinsClient
        }

        context("for when config is provided") {
            before {
                val a = getAction("github.com/test/repo.git", getBuild(getRevision(getBranch("some-branch", "some-sha1"))))
                coEvery { clouddriverService.getImagesByAccount("something", true) } returns listOf(
                    taggedImage("example/img-fake", "1", "some-commit", "digest1"),
                    taggedImage("example/img-real","4", "some-sha", "digest2")
                )
                coEvery { jenkinsClient.getGitDetails(any(),any()) } returns getScmDetails(a)
                coEvery { jenkinsClient.getBuilds(any())} returns getBuildList()
                coEvery { jenkinsClient.getBuild("some-project", 4)} returns getBuildsResult()
                coEvery { mockScmMaster.getCommitDetails("test", "repo", any()) } returns getCommitDetails()
            }

            test("fetching builds succeeds with proper data") {
                val b  = buildService.getBuilds("some-project", "some-slug", null, null, null)
                expectThat(b.size).equals(1)

                val buildData = b.first()
                expectThat(buildData) {
                    get { id }.isEqualTo("some-project-4")
                    get { properties.get("projectKey") as String }.isEqualTo("test")
                    get { properties.get("repoSlug") as String }.isEqualTo("repo")
                    get { result }.isEqualTo(Result.SUCCESS)
                    get { genericGitRevisions.size }.isEqualTo(1)
                    get { artifacts }.get { size }.isEqualTo(1)
                    get { artifacts }.get { first() }.get { name }.isEqualTo("example/img-real")
                }

                 expectThat(buildData.genericGitRevisions.first()) {
                    get { name }.isEqualTo("some-branch")
                    get { message }.isEqualTo("some-message")
                    get { sha1 }.isEqualTo("some-sha1")
                    get { branch }.isEqualTo("some-branch")
                    get { compareUrl }.isEqualTo("some-html-url")
                     get { committer }.isEqualTo("some-name")
                 }
            }

            context("when git url does not end with .git") {
                before {
                    val repoName1 = getAction("https://github.com/test1/repo1", getBuild(getRevision(getBranch("some-branch", "some-sha1"))))
                    val repoName2 = getAction("git@github.com:test2/repo2", getBuild(getRevision(getBranch("some-branch", "some-sha1"))))
                    coEvery { jenkinsClient.getGitDetails(any(),any()) } returnsMany listOf(getScmDetails(repoName1), getScmDetails(repoName2))
                    coEvery { mockScmMaster.getCommitDetails(any(), any(), any()) } returns getCommitDetails()
                }

                test("correct projectKey and repoSlug values are returned") {
                    val b  = buildService.getBuilds("some-project", "some-slug", null, null, null)
                    val c = buildService.getBuilds("some-project", "some-slug", null, null, null)
                    expectThat(b.size).equals(1)
                    expectThat(b.first()) {
                        get { properties.get("projectKey") as String }.isEqualTo("test1")
                        get { properties.get("repoSlug") as String }.isEqualTo("repo1")
                    }
                    expectThat(c.first()) {
                        get { properties.get("projectKey") as String }.isEqualTo("test2")
                        get { properties.get("repoSlug") as String }.isEqualTo("repo2")
                    }
                }
            }

            context("when no github action is available for build") {
                before {
                    coEvery { jenkinsClient.getGitDetails(any(), any()) } returns getScmDetails(null)
                }

                test("build data exists but with no git info") {
                    val b  = buildService.getBuilds("some-project", "some-slug", null, null, null)
                    expectThat(b.size).equals(1)
                    expectThat(b.first().genericGitRevisions).isEmpty()
                }
            }

            context("when no commit details are found") {
                before {
                    coEvery { mockScmMaster.getCommitDetails("test", "repo", any()) } throws Exception("not found")
                }

                test("committer details and artifact info are missing") {
                    val b  = buildService.getBuilds("some-project", "some-slug", null, null, null)
                    expectThat(b.size).equals(1)
                    expectThat(b.first().genericGitRevisions.size).isEqualTo(1)
                    expectThat(b.first().genericGitRevisions.first()) { get { committer }.isNull() }
                    expectThat(b.first().artifacts).isEmpty()
                }
            }

            context("docker throws error") {
                before {
                    coEvery { clouddriverService.getImagesByAccount(any(), any()) } throws Exception("not found")
                }

                test("committer details and artifact info are missing") {
                    expectCatching {
                        buildService.getBuilds("some-project", "some-slug", null, null, null)
                    }.isFailure()
                }
            }
        }

        context("when fetching build output") {
            context("when data is valid") {
                before {
                    coEvery { jenkinsClient.getBuildOutput("build", "4")} returns getResponse()
                }

                test("it returns output") {
                    val output = buildService.getBuildOutput("build-4")
                    expectThat(output).isEqualTo(mapOf("log" to "yolo"))
                }
            }

            context("when build output throws exception") {
                before {
                    coEvery { jenkinsClient.getBuildOutput(any(), any())} throws Exception("not found")
                }

                test("it returns empty output") {
                    expectCatching { buildService.getBuildOutput("build-4") }
                        .isSuccess()
                        .isEqualTo(mapOf("log" to ""))
                }
            }
        }
    }
}