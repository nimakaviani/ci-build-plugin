
## Example Jenkins deployment

This is an example Jenkins deployment which creates:

1. `PersistentVolumeClaim`
2. `Service` of LoadBalancer type
3. `ServiceAccounts` 
4. `Role` and `RoleBinidng` Note: this gives admin access within jenkins namespace

### Jenkins setup steps:

1. Create a Jenkins instance by `kubectl -n jenkins apply -f jenkinsDeployment.yaml`
2. Connect to your Jenkins instance, login, and install `Dcoker pipeline`, `Docker Commons`, and `Kubernetes` plugins in addition to default plugins.
3. Create a job. The name must match the application name in Spinnaker.
4. Create a GitHub repository. Copy `index.html`, `Dockerfile`, and `Jenkinsfile` to the root of the newly created repository.
5. Edit the `Jenkinsfile` and update the `DOCKER_REGISTRY` value with your own registry.
6. Create a Jenkins credentials named `dockerhub` which contains credentials to push to Docker Hub. 
6. [Setup a webhook trigger](https://dzone.com/articles/adding-a-github-webhook-in-your-jenkins-pipeline)
7. Specify script path as `Jenkinsfile`.

### Jenkins job flow:

1. Jenkins job is triggered by a webhook from a GtiHub repository.
2. Kubernetes pod is assigned for each job with docker in docker (dind) image. 
3. Docker images are built in the dind container with required labels, `buildNumber`, `commitId`,
`branch`, and `jobName`.
4. Built images are pushed to a Docker Hub registry using a credentials named `dockerhub`. (Be sure to update the jenkinsfile to point to your Docker registry)