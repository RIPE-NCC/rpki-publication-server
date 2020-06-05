package net.ripe.rpki.publicationserver.integration

import java.net.URI

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
  

  before {
    val logger = LoggerFactory.getLogger(this.getClass)    
    val conf = new AppConfig {
        override lazy val publicationServerTrustStoreLocation = "./test/resources/serverTrustStore.ks"
        override lazy val publicationServerTrustStorePassword = "12345"
    }   
    new PublicationServerApp(conf, logger).run()
  }

  test("should return a response with content-type application/rpki-publication") {

  }  

}
