package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{Files, Paths}

import akka.testkit.{TestActorRef, TestKit}
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.store._
import net.ripe.rpki.publicationserver.store.fs._
import org.scalatest.{BeforeAndAfterAll, Ignore}
import org.scalatest.mockito.MockitoSugar
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}

import scala.concurrent.duration._
import scala.language.postfixOps

object RepositoryStateTest {
  lazy val rootDir = Files.createTempDirectory("test_repo_state")
  lazy val rootDirName = rootDir.toString

  var retainPeriodOverride: Int = 1

  val theObjectStore = ObjectStore.get
  val theRsyncWriter = MockitoSugar.mock[RsyncRepositoryWriter]

  lazy val conf = new AppConfig {
    override lazy val unpublishedFileRetainPeriod = 1.second
    override lazy val snapshotSyncDelay = 1.second
    override lazy val rrdpRepositoryPath = rootDirName
  }
}

class RepositoryStateTest extends PublicationServerBaseTest with ScalatestRouteTest with Hashing with BeforeAndAfterAll {
  import RepositoryStateTest._

  private var serial: Long = _

  private def sessionDir = findSessionDir(rootDir).toString

  implicit val customTimeout = RouteTestTimeout(6000.seconds)

  lazy val theStateActor = TestActorRef(new StateActor(conf, testMetrics))
  def publicationService = new PublicationService(conf, theStateActor)

  deleteOnExit(rootDir)
  
  before {
    initStore()
    serial = 1L
    theObjectStore.clear()
    theStateActor.underlyingActor.preStart()
  }

  after {
    cleanStore()
  }

  override def afterAll() = {
    cleanUp()
  }

  test("should create snapshots and deltas") {

    val data = Base64("AAAAAA==")
    val publishXml = pubMessage("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer", data)

    val service = publicationService
    checkFileExists(Paths.get(sessionDir))
    checkFileExists(Paths.get(sessionDir, "1", "snapshot.xml"))

    // publish, withdraw and re-publish the same object to make
    // delta size larger than snapshot size
    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "2"))
    checkFileExists(Paths.get(sessionDir, "2", "snapshot.xml"))
    checkFileExists(Paths.get(sessionDir, "2", "delta.xml"))

    checkFileAbsent(Paths.get(sessionDir, "1", "snapshot.xml"))
  }

  test("should remove snapshot for serial older than the latest") {

    val data = Base64("AAAAAA==")
    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"
    val publishXml = pubMessage(uri, data)
    val withdrawXml = withdrawMessage(uri, hash(data))

    val service = publicationService

    checkFileExists(Paths.get(sessionDir))
    checkFileExists(Paths.get(sessionDir, "1", "snapshot.xml"))

    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "2"))
    checkFileExists(Paths.get(sessionDir, "2", "snapshot.xml"))
    checkFileAbsent(Paths.get(sessionDir, "1", "snapshot.xml"))

    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "3", "snapshot.xml"))
    checkFileAbsent(Paths.get(sessionDir, "2", "snapshot.xml"))
  }

//  test("should delete deltas in case their total size is bigger than the size of the request") {
//
//    val data = Base64("AAAAAA==")
//    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"
//    val publishXml = pubMessage(uri, data)
//    val withdrawXml = withdrawMessage(uri, hash(data))
//
//    val service = publicationService
//
//    checkFileExists(Paths.get(sessionDir, "1", "snapshot.xml"))
//
//    // publish, withdraw and re-publish the same object to make
//    // delta size larger than snapshot size
//
//    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }
//
//    checkFileExists(Paths.get(sessionDir, "2", "delta.xml"))
//
//    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }
//
//    checkFileExists(Paths.get(sessionDir, "3", "delta.xml"))
//
//    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }
//
//    checkFileExists(Paths.get(sessionDir, "4", "delta.xml"))
//
//    Thread.sleep(retainPeriodOverride); // wait until delta deletion time comes
//
//    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }
//
//    checkFileExists(Paths.get(sessionDir, "5", "delta.xml"))
//
//    // it should remove deltas 2 and 3 because together with 4th they constitute
//    // more than the last snapshot
//    checkFileAbsent(Paths.get(sessionDir, "2", "delta.xml"))
//    checkFileAbsent(Paths.get(sessionDir, "3", "delta.xml"))
//  }

  private def pubMessage(uri: String, base64: Base64, hash: Option[Hash] = None) = {
    val hashAttr = hash.map(h => s""" hash="${h.hash}" """).getOrElse("")
    s"""<msg
        type="query"
        version="3"
        xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
          <publish
          uri="$uri" $hashAttr>${base64.value}</publish>
        </msg>"""
  }

  private def withdrawMessage(uri: String, hash: Hash) =
    s"""<msg
        type="query"
        version="3"
        xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
          <withdraw
          uri="$uri" hash="${hash.hash}"></withdraw>
        </msg>"""


}
