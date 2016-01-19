package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import akka.testkit.TestActorRef
import akka.testkit.TestKit._
import net.ripe.rpki.publicationserver.model.Delta
import net.ripe.rpki.publicationserver.store._
import net.ripe.rpki.publicationserver.store.fs._
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mock.MockitoSugar
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

object RepositoryStateTest {
  val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test_pub_server_")
  rootDir.toFile.deleteOnExit()
  val rootDirName = rootDir.toString

  var retainPeriodOverride: Int = 1

  val theObjectStore = new ObjectStore
  val theRsyncWriter = MockitoSugar.mock[RsyncRepositoryWriter]


  lazy val conf = new AppConfig {
    override lazy val unpublishedFileRetainPeriod = 1.millisecond
    override lazy val snapshotSyncDelay = 1.millisecond
    override lazy val rrdpRepositoryPath = rootDirName
  }
}

class RepositoryStateTest extends PublicationServerBaseTest with ScalatestRouteTest with Hashing with BeforeAndAfterAll {
  import RepositoryStateTest._

  private var serial: Long = _

  private var sessionDir: String = _

  // TODO Remove (of tune) it after debugging
  implicit val customTimeout = RouteTestTimeout(6000.seconds)

  def publicationService = TestActorRef(new PublicationServiceActor(conf)).underlyingActor

  before {
    cleanDir(rootDir.toFile)
    serial = 1L
    theObjectStore.clear()
    Migrations.initServerState()
    sessionDir = findSessionDir(rootDir).toString
    when(RepositoryStateTest.theRsyncWriter.writeDelta(any[Delta])).thenReturn(Try {})
  }

  override def afterAll() = {
    cleanDir(rootDir.toFile)
    Files.deleteIfExists(rootDir)
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

    checkFileExists(Paths.get(sessionDir, "1", "snapshot.xml"))

    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "2", "snapshot.xml"))
    checkFileAbsent(Paths.get(sessionDir, "1", "snapshot.xml"))

    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "3", "snapshot.xml"))
    checkFileAbsent(Paths.get(sessionDir, "2", "snapshot.xml"))
  }

  test("should delete deltas in case their total size is bigger than the size of the request") {

    val data = Base64("AAAAAA==")
    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"
    val publishXml = pubMessage(uri, data)
    val withdrawXml = withdrawMessage(uri, hash(data))

    val service = publicationService

    checkFileExists(Paths.get(sessionDir, "1", "snapshot.xml"))

    // publish, withdraw and re-publish the same object to make
    // delta size larger than snapshot size

    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "2", "delta.xml"))

    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "3", "delta.xml"))

    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "4", "delta.xml"))

    Thread.sleep(retainPeriodOverride); // wait until delta deletion time comes

    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    checkFileExists(Paths.get(sessionDir, "5", "delta.xml"))

    // it should remove deltas 2 and 3 because together with 4th they constitute
    // more than the last snapshot
    checkFileAbsent(Paths.get(sessionDir, "2", "delta.xml"))
    checkFileAbsent(Paths.get(sessionDir, "3", "delta.xml"))
  }

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

  private def cleanDir(dir: File) = {
    def cleanDir_(file: File): Unit =
      Option(file.listFiles).map(_.toList).getOrElse(Nil).foreach { f =>
        if (f.isDirectory)
          cleanDir_(f)
        f.delete
      }

    if (dir.isDirectory)
      cleanDir_(dir)
  }

  def findSessionDir(path: Path): File = {
    val maybeFiles: Option[Array[File]] = Option(path.toFile.listFiles)

    awaitCond(maybeFiles.exists(_.exists { f =>
      Try(UUID.fromString(f.getName)).isSuccess
    }), max = waitTime)

    maybeFiles.flatMap(_.find { f =>
      Try(UUID.fromString(f.getName)).isSuccess
    }).get
  }

}
