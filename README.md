RPKI Publication Server
=======================

This is the RIPE NCC's implementation of [RFC 8182 - The RPKI Repository Delta Protocol](https://tools.ietf.org/html/rfc8182)
and a draft of [RFC 8181 - A Publication Protocol for the Resource Public Key Infrastructure](https://tools.ietf.org/html/rfc8181).

This implementation differs from the final specification in the following key areas:
  * Mutual TLS is used instead of CMS wrapping of objects.
  * Publication XML messages follow [draft-ietf-sidr-publication-07](https://tools.ietf.org/html/draft-ietf-sidr-publication-07) instead of RFC 8181.

The publication server is meant to be used with a single trusted client (c.f.
multiple delegated Certification Authorities).

The publication server relies on a reverse proxy for TLS on the port used for
RRDP. Since all Relying Party implementations require HTTPS urls for the RRDP
repository, for production usage the publication server **MUST** be deployed
behind a reverse proxy that performs TLS termination, with a trusted network
link between reverse proxy and publication server.

The produced RRDP repository is in accordance with RFC 8182 and is accepted by
all known Relying Party implementations.

License
---

Copyright (c) 2015-2023 RIPE NCC All rights reserved.

This software and all its separate source code is licensed under the terms of
the BSD 3-Clause License. If a copy of the license was not distributed to you,
you can obtain one at https://github.com/RIPE-NCC/rpki-publication-server/blob/main/LICENSE.txt.

Building the project
--------------------

This project uses [SBT](http://www.scala-sbt.org). You also need Java 8 to build it.
It has been tested with Oracle's JDK, but should work with other implementations as well.

Use `sbt universal:packageZipTarball` to create a distribution archive from sources.

For running and testing locally one would need to create PostgreSQL databases

    createuser -R -S -D pubserver
    createdb -O pubserver pubserver
    createdb -O pubserver pubserver_test 
    echo 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp";' | psql pubserver_test 

Running the server
------------------

Unpack the distribution archive into the directory of your choice.

The publication server 2.0 and higher requires a PostgreSQL
database. There is currently no migration from the Xodus database used
by the 1.0 publication server. To create the initial database run:

```
createuser -P pubserver
createdb -O pubserver pubserver
echo 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp";' | psql pubserver
```

The publication server will automatically manage the database schema on startup.

Inspect *conf/rpki-publication-server.default.conf* file and update it according to your preferences.
Note that if the machine it runs on does not have IPv6, `server.address` needs
to be `0.0.0.0` to prevent errors during startup or tests.

Use *bin/rpki-publication-server.sh* script to start and stop the server:

> $ bin/rpki-publication-server.sh start -c conf/my-server.conf
>
> $ bin/rpki-publication-server.sh stop -c conf/my-server.conf


Configuring HTTPS for publication protocol
------------------------------------------

The publication server **requires** HTTPS for the publication protocol with client authentication. This mitigates the
risk of publication server from being launched without HTTPS in a production setting by accident (ROS 2.15.3).

**Keep in mind** that client authentication needs to be setup both on the side of the publisher as well.

To create self-signed server's certificate, use following commands:

* Generate the server key pair and certificate:

> $ keytool -genkey -alias pub-server -keystore serverKeyStore.ks -keyalg RSA -keysize 4096

* Export server's certificate to a file (to be used by a client):

> $ keytool -export -alias pub-server -keystore serverKeyStore.ks -rfc -file myServer.cert

* Import server's certificate into java client's keystore:

> $ keytool -import -alias pub-server -file myServer.cert -keystore clientTrustStore.ks

NOTE: You have to use the same password for the key and for the keystore.


To enable client certificate validation on the publication server, set the publication.server.truststore.\\* properties.

Use following commands to generate and install client's certificate into server's truststore:

* Generate client's key pair and certificate:

> $ keytool -genkey -alias pub-client -keystore clientKeyStore.ks

* Export client's certficate:

> $ keytool -export -alias pub-client -keystore clientKeyStore.ks -rfc -file aClient.cert

* Install client's certificate in the server's trust store:

> $ keytool -import -alias pub-client -file aClient.cert -keystore serverTrustStore.ks


Running the docker container
----------------------------

To run a released version of the container the following command can be used 

```
docker run -it \
  -p 7766:7766 \
  -p 7788:7788 \
  -v `pwd`/src/test/resources/certificates/:/conf/ssl \
  -e ENABLE_SSL=on \
  -e KEYSTORE_PATH=/conf/ssl/serverKeyStore.ks \
  -e TRUSTSTORE_PATH=/conf/ssl/serverTrustStore.ks \
  -e KEYSTORE_PASSWORD="123456" \
  -e TRUSTSTORE_PASSWORD="123456" \
  -e POSTGRES_URL="jdbc:postgresql://host.docker.internal/pubserver" \
  ghcr.io/ripe-ncc/rpki-publication-server:latest
```

To run the container build from the source code the command becomes

```
sbt assembly
docker build . -t rpki-publication-server
docker run -it \
  -p 7766:7766 \
  -p 7788:7788 \
  -v `pwd`/src/test/resources/certificates/:/conf/ssl \
  -e ENABLE_SSL=on \
  -e KEYSTORE_PATH=/conf/ssl/serverKeyStore.ks \
  -e TRUSTSTORE_PATH=/conf/ssl/serverTrustStore.ks \
  -e KEYSTORE_PASSWORD="123456" \
  -e TRUSTSTORE_PASSWORD="123456" \
  -e POSTGRES_URL="jdbc:postgresql://host.docker.internal/pubserver" \
  --rm rpki-publication-server
```

#### Environment variables
    * `DATABASE_PATH`: Path to the internal database (default: `/data/db`)
    * `RRDP_REPOSITORY_PATH`: Path to RRDP data in container (default: `/data/rrdp`).
    * `RRDP_REPOSITORY_URI`: Base URI of the RRDP repository.
    * `KEYSTORE_PATH`: path of the keystore (on mounted volume)
    * `KEYSTORE_PASSWORD`: keystore password.
    * `TRUSTSTORE_PATH`: path of the truststore (on mounted volume)
    * `TRUSTSTORE_PASSWORD`: truststore password.

Testing in vagrant
------------------

Some of the tests were failing on Linux but not on OS X. A laptop with a high
number of cores was the most reliable way of reproducing the test failures. The
source is mounted in `/src` in the container.

If you want to run the tests in the container:
```
$ vagrant up
... wait for VM to start ...
$ vagrant ssh
... you get a SSH terminal in the vm ...
$ cd /src
$ sbt clean test
```

Architecture overview
----------------------

The main entry point is PublicationService. The class PgStore operates with the database.
The concrete structure of the database is hidden behind SQL functions and views.

Every publish/withdraw results in
a) insertion/deletion in `objects` table;
b) insertion into `object_log` table.

From time to time we start a transaction that does the following:
1) "freeze" the version, i.e. generate new serial (with some corner cases with empty tables in the beginning);
2) read the objects to generate snapshot.xml for the latest frozen serial;
3) read the object_log to generate the delta;
4) clean up older versions if needed (they are too old, or their overall size is too big);
5) clean up corresponding entries in object_log;
6) cleanup files in the file system.

Important: transaction that generate the files needs to run with at least repeatable read isolation level to capture
the current state of the whole database. Repeatable read in PG is stronger than the standard requires and it is
enough for our purposes https://www.postgresql.org/docs/12/transaction-iso.html#XACT-REPEATABLE-READ.
