# -*- mode: ruby -*-
# vi: set ft=ruby :
Vagrant.configure("2") do |config|
  config.vm.box = "generic/fedora32"

  config.vm.network "forwarded_port", guest: 7766, host: 7766, host_ip: "127.0.0.1"
  config.vm.network "forwarded_port", guest: 7788, host: 7788, host_ip: "127.0.0.1"

  # Put source in /src but not in /vagrant (because that is often rsync)
  config.vm.synced_folder ".", "/src"
  config.vm.synced_folder "../data", "/data"
  config.vm.synced_folder ".", "/vagrant", disabled: true

  config.vm.provider "virtualbox" do |vb|
    # Customize the amount of memory on the VM:
    vb.memory = "4096"
    vb.cpus = 8
  end

  config.vm.provision "shell", inline: <<-SHELL
    echo "Updating system and installing git, jdk, and postgresql"
    dnf update -y --refresh
    dnf install -y java-11-openjdk postgresql-server git

    echo "Setting up postgresql"
    postgresql-setup --initdb --unit postgresql
    systemctl stop postgresql || true
    sed -i -e 's/ident/trust/g' /var/lib/pgsql/data/pg_hba.conf
    systemctl start postgresql

    echo "Creating application database"
    cd /tmp
    sudo -u postgres createuser -R -S -D pubserver
    sudo -u postgres createdb -O pubserver pubserver
    sudo -u postgres createdb -O pubserver pubserver_test

    echo "Creating vagrant postgres user"
    sudo -u postgres createuser -s vagrant

    echo "Installing SBT"
    curl https://bintray.com/sbt/rpm/rpm | sudo tee /etc/yum.repos.d/bintray-sbt-rpm.repo
    sudo dnf install -y sbt
  SHELL
end
