RPKI Publication Server
=======================

This is the RIPE NCC's implementation of [A Publication Protocol for the Resource Public Key Infrastructure] 
(https://tools.ietf.org/html/draft-weiler-sidr-publication) and [RPKI Repository Delta Protocol]
(https://tools.ietf.org/wg/sidr/draft-ietf-sidr-delta-protocol/).

Building the project
--------------------

This project uses [SBT](http://www.scala-sbt.org). You also need Java 8 to build it.
It has been tested with Oracle's JDK, but should work with other implementations as well.

Use `sbt universal:packageZipTarball` to create a distribution archive from sources.

For running and testing locally one would need to create PostgreSQL databases

    createuser -R -S -D pubserver
    createdb -O pubserver pubserver
    createdb -O pubserver pubserver_test

Running the server
------------------

Unpack the distribution archive into the directory of your choice.

Inspect *conf/rpki-publication-server.default.conf* file and update it according to your preferences.

Use *bin/rpki-publication-server.sh* script to start and stop the server:

> $ bin/rpki-publication-server.sh start -c conf/my-server.conf
>
> $ bin/rpki-publication-server.sh stop -c conf/my-server.conf


Configuring HTTPS for publication protocol
------------------------------------------

It is possible to use HTTPS for publication protocol, with or without client authentication.

To enable HTTPS for publication protocol, set publication.spray.can.server.ssl-encryption parameter to "on", and 
define publication.server.keystore.\* properties.

To create self-signed server's certificate, use following commands:

* Generate the server key pair and certificate:

> $ keytool -genkey -alias pub-server -keystore serverKeyStore.ks

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

```
docker build . -t rpki-publication-server
docker run -it \
  -p 7766:7766 \
  -p 7788:7788 \
  -v `pwd`/ssl:/conf/ssl \
  -e KEYSTORE_PATH=/conf/ssl/serverKeyStore.ks \
  -e TRUSTSTORE_PATH=/conf/ssl/serverTrustStore.ks \
  -e KEYSTORE_PASSWORD="123456" \
  -e TRUSTSTORE_PASSWORD="123456" \
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
3) read the object_log to generate the delta and update rsync repository;
4) clean up older versions if needed (they are too old, or their overall size is too big);
5) clean up corresponding entries in object_log;
6) cleanup files in the file system.

Important: transaction that generate the files needs to run with at least repeatable read isolation level to capture 
the current state of the whole database. Repeatable read in PG is stronger than the standard requires and it is 
enough for our purposes https://www.postgresql.org/docs/12/transaction-iso.html#XACT-REPEATABLE-READ.
