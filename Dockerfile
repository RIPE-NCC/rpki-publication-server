FROM docker-registry.ripe.net/swe/gitlab-ci/ebclient:ebcli

COPY target/universal/*.tgz . 

