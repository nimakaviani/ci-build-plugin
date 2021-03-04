# Spinnaker CiBuild Plugin

## Configuration

In Deck's `settings.js` set the following value:

```json
{"feature":{"ci": true}}
```
In Igor's configuration file, set the following:

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
you want the build data to be read from like the following (notice `ciEnabled:true`
for the selected Jenkins master):

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

Finally, in your `igor.yml` file, enable the plugin:

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

## Build
run `./gradlew releaseBundle` and copy the created zip file to
`/tmp/plugins` or your plugin folder of choice. Make sure that the folder is
writable for the plugin to be unzipped in.