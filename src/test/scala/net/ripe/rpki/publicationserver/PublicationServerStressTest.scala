package net.ripe.rpki.publicationserver

import java.io.File
import java.util.UUID

import akka.testkit.{TestActorRef, TestKit}
import net.ripe.rpki.publicationserver.Binaries.Base64
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import org.apache.commons.io.FileUtils
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.Try
import java.nio.file.Files

class PublicationServerStressTest extends PublicationServerBaseTest with ScalatestRouteTest with Hashing with BeforeAndAfterAll{
  
    val rootDir = Files.createTempDirectory("test_pub_server_stress_test_")

    val rootDirName = rootDir.toAbsolutePath.toString

    val conf = new AppConfig {
        override lazy val unpublishedFileRetainPeriod = 1.second
        override lazy val snapshotSyncDelay = 1.second
        override lazy val rrdpRepositoryPath = rootDirName
    }

    def theStateActor = TestActorRef(new StateActor(conf, testMetrics))

    lazy val publicationService = new PublicationService(conf, theStateActor)    

  override def beforeAll() {
    conf.rsyncRepositoryMapping.foreach(z => cleanDir(z._2))           
    initStore()
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    cleanUp()
    cleanDir(rootDir)
  }

  def publishAndRetrieve(clientId: ClientId, promise: Promise[Unit]) = {
    val listXml = getFile("/list.xml").mkString
    val content = (1 to 20).map(UUID.randomUUID.toString.replace("-", "0")).mkString
    val uri = "rsync://localcert.ripe.net/" + clientId.value
    val expectedListResponse = listResponse(uri, hash(Base64(content)))
    val publish = publishRequest(uri, content)

    POST(s"/?clientId=${clientId.value}", publish) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)

      POST(s"/?clientId=${clientId.value}", listXml) ~> publicationService.publicationRoutes ~> check {
        promise.complete {
          Try {
            val response = responseAs[String]
            trim(response) should be(trim(expectedListResponse))
          }
        }
      }
    }
  }

  val oneSecond = 1000000000L

  test("should get correct result for one client request") {
    val futures = getPublishRetrieveFutures(1)
    futures.foreach(f => Await.ready(f, Duration.fromNanos(oneSecond * 10)))
  }

  test("should get correct results for 100 sequential client requests") {
    val futures = getPublishRetrieveFutures(10)
    futures.foreach(f => {
      Await.ready(f, Duration.fromNanos(oneSecond * 10))
    })
  }

  test("should get correct results for 100 parallel client requests") {
    val futures = getPublishRetrieveFutures(100)
    val futureSequence = Future.sequence(futures)
    Await.ready(futureSequence, Duration.fromNanos(oneSecond * 100))
  }

  def getPublishRetrieveFutures(nr: Int) = {
    val futures = (0 until nr).map(i => {
      val promise = Promise[Unit]()
      publishAndRetrieve(ClientId(i.toString), promise)
      promise.future
    })
    futures
  }

  def publishRequest(uri: String, content: String) =
    s"""
       |<msg
       |   type="query"
       |   version="3"
       |   xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
       | <publish
       |     uri="$uri">
       | $content
       | </publish>
       |</msg>
    """.stripMargin

  def listResponse(uri: String, hash: Hash) =
    s"""
       |<msg
       |       type="reply"
       |       version="3"
       |       xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
       |   <list uri="$uri"
       |         hash="${hash.hash}"/>
       |</msg>
    """.stripMargin
}
