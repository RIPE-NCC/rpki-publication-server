FROM registry.gitlab.com/gitlab-org/cloud-deploy/aws-base:latest

COPY target/universal/* . 

RUN  ls -al

