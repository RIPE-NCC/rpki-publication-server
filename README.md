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


Architecture overview
----------------------

The main entry point is PublicationService. The class PgStore operates with the database. 
The concrete structure of the database is hidden behind SQL functions and views.

Every publish/withdraw results in 
a) insertion/deletion in `objects` and `object_urls` tables;
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

