package net.ripe.rpki.publicationserver.integration

import java.net.URL

import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.fs.RsyncRepositoryWriter
import net.ripe.rpki.publicationserver.PublicationServerBaseTest
import net.ripe.rpki.publicationserver.AppConfig
import net.ripe.rpki.publicationserver.Hashing
import org.slf4j.LoggerFactory
import net.ripe.rpki.publicationserver.PublicationServerApp
import java.nio.file._
import java.net.URI

class PublicationIntegrationTest
    extends PublicationServerBaseTest
    with Hashing {

  private var server: PublicationServerApp = null
  private var client: PublicationServerClient = null

  override def beforeAll = {
    val logger = LoggerFactory.getLogger(this.getClass)

    val rsyncRootDir = Files.createTempDirectory(Paths.get("/tmp"), "test_pub_server_rsync_")
    val storeDir = Files.createTempDirectory(Paths.get("/tmp"), "test_pub_server_store_")
    rsyncRootDir.toFile.deleteOnExit()
    storeDir.toFile.deleteOnExit()

    val conf = new AppConfig {
      override lazy val rsyncRepositoryMapping = Map(
        URI.create("rsync://localhost:10873/repository") -> rsyncRootDir
      )

      override lazy val storePath = storeDir.toString()
      override lazy val publicationServerTrustStoreLocation =
        "./src/test/resources/certificates/serverTrustStore.ks"
      override lazy val publicationServerKeyStoreLocation =
        "./src/test/resources/certificates/serverKeyStore.ks"
      override lazy val publicationServerTrustStorePassword = "123456"
      override lazy val publicationServerKeyStorePassword = "123456"
    }
    server = new PublicationServerApp(conf, logger)
    server.run()
    client = new PublicationServerClient()
  }

  test("should publish an object and don't accept repeated publish") {
    val url = "rsync://localhost:10873/repository/test1"
    val base64 = "abababababab"
    val hashStr = hash(Base64(base64)).hash

    val response = client.publish("client1", url, base64)
    response.contains(s"""<publish uri="${url}"/>""") should be(true)

    client.list("client1")
        .contains(s"""<list uri="$url" hash="$hashStr"/>""") should be(true)    

    client.list("client2").contains("<list") should be(false)    

    val responseError = client.publish("client1", url, "babababa")        
    responseError.contains(s"""Tried to insert existing object [$url].""") should be(true)
  }

  test("should publish an object and withdraw it") {
    val url = "rsync://localhost:10873/repository/test2"
    val base64 = "abababababab"
    val hashStr = hash(Base64(base64)).hash       
    val response = client.publish("client1", url, base64)    
    response.contains(s"""<publish uri="${url}"/>""") should be(true)
    
    client.list("client1")
        .contains(s"""<list uri="$url" hash="$hashStr"/>""") should be(true)            
     
    client.withdraw("client1", url, hashStr)
        .contains(s"""<withdraw uri="${url}"/>""") should be(true)

    client.list("client1")
        .contains(s"""<list uri="$url" hash="$hashStr"/>""") should be(false)    
  }

}
