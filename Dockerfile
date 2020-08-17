FROM docker-registry.ripe.net/swe/gitlab-ci/awsebcli:3.18.2

COPY target/universal/*.tgz . 

