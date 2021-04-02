# Spinnaker CiBuild Plugin

## Requirements

If your Spinnaker release is 1.25.2 or older, you need to update the following components to 
have the respective commits:

- Clouddriver: spinnaker/clouddriver@f900ea6
- Igor: spinnaker/igor@074d9aa

You also need to have one (and currently only one) Jenkins installation enaled for use with the plugin.
The Jenkins deployment needs to be accessible to Spinnaker.

## Configuration

### Deck
For _Deck_, in your `settings-local.js` turn on the CI feature like the following:

```json
window.spinnakerSettings.feature.ci = true
```

### Igor
In _Igor_'s configuration file, enable CI as follows:

```yaml
services:
  ci: 
    enabled: true
```
Currently the plugin integrates with Jenkins as the CI 
system and DockerHub as the container registry. This requires setting up
Jenkins configuration in Igor and also instructing Clouddriver on
how to pull image metadata from DockerHub.

For _Igor_, mark the Jenkins master instance
you want the build data to be read from by adding `ciEnabled:true`
to the selected Jenkins master instance. The plugin currently supports only one Jenkins instance.
Also you need to update the address to match the address where Jenkins is accessible.

```yaml
jenkins:
  enabled: true
  masters:
  - address: http://localhost:8080
    name: my-jenkins
    password: xxx
    permissions: {}
    username: xxx
    ciEnabled: true
```

Also, in your `igor.yml` file, enable the plugin:

```yaml
spinnaker:
  extensibility:
    plugins-root-path: /tmp/plugins
    plugins:
      aws.CiBuildPlugin:
        enabled: true
    repositories: {}
    strict-plugin-loading: false
```

### Clouddriver

For _Clouddriver_, in the docker registry configuration, enable both
`trackDigests` and `inspectDigests` as shown below:

```yaml
dockerRegistry:
  accounts:
  - address: https://index.docker.io
    trackDigests: true
    inspectDigests: true
    name: my-registry
    repositories:
    - example/service
```

## Usage 
### Application Setup
One you have everything deployed, you need to create a new Spinnaker application with git information 
that relate the Spinnaker application to a Jenkins' job in your Jenkins deployment. To do so, when creating
an application, choose GitHub configuration in Spinnaker and have your `Repo Name` match the Jenkins job name
in your Jenkins deployment. As shown below, the name `test` is common between the `Repo Name` and the Jenkins job.

![Repo Setup](/docs/images/repoSetup.png?raw=true)

If everything goes well, the Build section in your Spinnaker deployment should be populated with build 
information from Jenkins.

![CiBuild UI](/docs/images/CiBuild.png?raw=true)

### Jenkins Docker Artifact Creation

_Important_: When building a docker artifact using Jenkins, you need to ensure that Jenkins 
adds _four_ labels to the created docker artifact, for the metadata to be successfully picked up
by clouddriver for future association of commits and builds with a docker artifact. These four
labels are `buildNumber`, `commitId`, `branch`, and `jobName`.

Your docker build should look something like the following:

```bash
commit_sha = $(git rev-parse HEAD)
branch_name = $(git rev-parse --abbrev-ref HEAD)

docker build \
    --label "buildNumber=${BUILD_NUMBER}" \
    --label "commitId=${commit_sha}" \
    --label "branch=${branch_name}" \
    --label "jobName=${JOB_NAME}" \
    -t [USER]/[IMAGE]:[TAG] -f Dockerfile .
```

## Build and Test
### Build Locally
run `./gradlew build -x test && ./gradlew releaseBundle` and copy the created zip file to
`/tmp/plugins` or your plugin folder of choice. Make sure that the folder is
writable for the plugin to be unzipped in.

### Test Releases
Under the `hack/` folder, you will find a script that allows you to create a 
release of the plugin and push it to an Amazon S3 repository which you can then use for test purposes.

For the `hack/build.sh` script to work, in addition to your gradle and java installations, 
you need to have the AWS CLI and `jq` installed. The `hack/build.sh` script assumes you already have
a bucket (default `ci-build-plugin-bucket`) in a given region (default `us-west-2`).

Running the script, the plugin and the metadata `plugin.json` files are pushed to the bucket and then you can
use the referenced artifact in your Igor configuration to test the plugin in deployment.