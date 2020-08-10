FROM registry.gitlab.com/gitlab-org/cloud-deploy/aws-base:latest

COPY target/universal/*.tgz . 

RUN  apt-get update && apt-get install -y \
    git build-essential zlib1g-dev libssl-dev libncurses-dev \
    libffi-dev libsqlite3-dev libreadline-dev libbz2-dev

RUN git clone https://github.com/aws/aws-elastic-beanstalk-cli-setup.git
RUN ./aws-elastic-beanstalk-cli-setup/scripts/bundled_installer
ENV PATH="${PATH}:/root/.ebcli-virtual-env/executables:/root/.pyenv/versions/3.7.2/bin"


