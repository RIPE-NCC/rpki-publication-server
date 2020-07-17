package net.ripe.rpki.publicationserver.integration

import java.net.URL

import akka.testkit.{TestActorRef, TestKit}
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

import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.HttpCharsets

class PublicationIntegrationTest
    extends PublicationServerBaseTest
    with Hashing {

  private var server: PublicationServerApp = null
  private var client: PublicationServerClient = null

  override def beforeAll = {

    initStore()

    val logger = LoggerFactory.getLogger(this.getClass)

    val rsyncRootDir = Files.createTempDirectory( "test_pub_server_rsync_")
    val storeDir = Files.createTempDirectory("test_pub_server_store_")
    deleteOnExit(rsyncRootDir)
    deleteOnExit(storeDir)

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

  override def afterAll(): Unit = {
    cleanStore()
    TestKit.shutdownActorSystem(client.system)
    TestKit.shutdownActorSystem(server.system)
  }

  // NOTE: test order is important

  test("should publish an object and don't accept repeated publish") {
    val url = "rsync://localhost:10873/repository/test1"
    val base64 = generateSomeBase64()
    val hashStr = hash(Base64(base64)).hash

    val response = client.publish("client1", url, base64)
    response should include(s"""<publish uri="${url}"/>""")

    client.list("client1") should
        include(s"""<list uri="$url" hash="$hashStr"/>""")

    client.list("client2") should not include("<list")

    forMetrics { metrics =>
        metrics should include("""rpkipublicationserver_object_operations_total{operation="publish",} 1.0""")
        metrics should include("""rpkipublicationserver_objects_last_received{operation="publish",}""")
    }

    val responseError = client.publish("client1", url, "babababa")
    responseError should include(s"""Tried to insert existing object [$url].""")

    forMetrics { metrics =>
        metrics should include("""rpkipublicationserver_object_operations_total{operation="publish",} 1.0""")
        metrics should include("""rpkipublicationserver_objects_failure_total{operation="add",} 1.0""")
    }
  }

  test("should publish an object and withdraw it") {
    val url = "rsync://localhost:10873/repository/test2"
    val base64 = generateSomeBase64()
    val hashStr = hash(Base64(base64)).hash
    val clientId = "client1"
    val response = client.publish(clientId, url, base64)
    response should include(s"""<publish uri="${url}"/>""")

    forMetrics { metrics =>
        metrics should include("""rpkipublicationserver_object_operations_total{operation="publish",} 2.0""")
    }

    client.list(clientId) should
        include(s"""<list uri="$url" hash="$hashStr"/>""")

    // try to use wrong hash for withdrawing
    val wrongHash = hash(Base64(generateSomeBase64())).hash
    val w = client.withdraw(clientId, url, wrongHash)
    w should include(s"""<report_error error_code="NonMatchingHash">""")
    w should include(s"""Cannot withdraw the object [${url}], hash doesn't match, passed ${wrongHash}, but existing one is $hashStr""")

    forMetrics { metrics =>
        metrics should include("""rpkipublicationserver_objects_failure_total{operation="delete",} 1.0""")
        metrics should not include("""rpkipublicationserver_object_operations_total{operation="withdraw",}""")
    }

    client.withdraw(clientId, url, hashStr) should
        include(s"""<withdraw uri="${url}"/>""")

    forMetrics { metrics =>
        metrics should include("""rpkipublicationserver_objects_failure_total{operation="delete",} 1.0""")
        metrics should include("""rpkipublicationserver_object_operations_total{operation="withdraw",} 1.0""")
    }

    client.list(clientId) should not
        include(s"""<list uri="$url" hash="$hashStr"/>""")

    // try to withdraw the second time
    client.withdraw(clientId, url, hashStr) should
        include(s"""No object [$url] found.""")

    forMetrics { metrics =>
        metrics should include("""rpkipublicationserver_object_operations_total{operation="publish",} 2.0""")
        metrics should include("""rpkipublicationserver_object_operations_total{operation="withdraw",} 1.0""")
        metrics should include("""rpkipublicationserver_objects_failure_total{operation="delete",} 2.0""")
    }
  }

 test("should publish an object and replace it") {
    val url = "rsync://localhost:10873/repository/test_replace"
    val base64 = generateSomeBase64()
    val hashStr = hash(Base64(base64)).hash
    val clientId = "client3"

    client.publish(clientId, url, base64) should
        include(s"""<publish uri="${url}"/>""")

    client.list(clientId) should
        include(s"""<list uri="$url" hash="$hashStr"/>""")

    // try to use wrong hash for replacing
    val newBase64 = generateSomeBase64()
    val wrongHash = hash(Base64(generateSomeBase64)).hash
    val response = client.publish(clientId, url, wrongHash, newBase64)
    response should include(s"""<report_error error_code="NonMatchingHash">""")
    response should include(s"""Cannot republish the object [${url}], hash doesn't match, passed ${wrongHash}, but existing one is $hashStr""")

    forMetrics { metrics =>
        metrics should  include("""rpkipublicationserver_objects_failure_total{operation="replace",} 1.0""")
    }

    client.publish(clientId, url, hashStr, newBase64) should
         include(s"""<publish uri="${url}"/>""")

    forMetrics { metrics =>
        metrics should include("""rpkipublicationserver_object_operations_total{operation="publish",} 4.0""")
        metrics should include("""rpkipublicationserver_object_operations_total{operation="withdraw",} 2.0""")
    }

  }

  def forMetrics(f: String => Unit) = {
      f(client.getMetrics())
  }

  private def generateSomeBase64() = {
      val randomBytes = Array.fill(20)((scala.util.Random.nextInt(256) - 128).toByte)      
      Bytes.toBase64(Bytes(randomBytes)).value      
  }


}
