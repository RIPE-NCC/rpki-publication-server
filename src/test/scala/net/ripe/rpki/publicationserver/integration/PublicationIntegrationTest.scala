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

object Store {
  val objectStore = ObjectStore.get
}

class PublicationTest extends PublicationServerBaseTest with Hashing {
  
  private var server : PublicationServerApp = null
  private var client : PublicationServerClient = null

 override def beforeAll = {
    val logger = LoggerFactory.getLogger(this.getClass)    
    val conf = new AppConfig {
        override lazy val publicationServerTrustStoreLocation = "./src/test/resources/certificates/serverTrustStore.ks"
        override lazy val publicationServerKeyStoreLocation   = "./src/test/resources/certificates/serverKeyStore.ks"
        override lazy val publicationServerTrustStorePassword = "123456"        
        override lazy val publicationServerKeyStorePassword   = "123456"
    }       
    server = new PublicationServerApp(conf, logger)
    server.run()    
    client = new PublicationServerClient()    
 }  

  test("should return a response with content-type application/rpki-publication") {            
      val response = client.send("client1", "rsync://blabla", "abababababab")      
      println("response = " + response)
  }  

}
