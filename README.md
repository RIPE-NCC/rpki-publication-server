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
docker run -it \
  -p 7766:7766 \
  -p 7788:7788 \
  -v `pwd`/ssl:/conf/ssl \
  -e KEYSTORE_PATH=/conf/ssl/serverKeyStore.ks \
  -e TRUSTSTORE_PATH=/conf/ssl/serverTrustStore.ks \
  -e KEYSTORE_PASSWORD="123456" \
  -e TRUSTSTORE_PASSWORD="123456" \
  --rm <image_name>
```

#### Environment variables
    * `DATABASE_PATH`: Path to the internal database (default: `/data/db`)
    * `RRDP_REPOSITORY_PATH`: Path to RRDP data in container (default: `/data/rrdp`).
    * `RRDP_REPOSITORY_URI`: Base URI of the RRDP repository.
    * `KEYSTORE_PATH`: path of the keystore (on mounted volume)
    * `KEYSTORE_PASSWORD`: keystore password.
    * `TRUSTSTORE_PATH`: path of the truststore (on mounted volume)
    * `TRUSTSTORE_PASSWORD`: truststore password.
