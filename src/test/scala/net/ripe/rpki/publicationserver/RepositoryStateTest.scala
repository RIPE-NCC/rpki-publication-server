package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{LinkOption, Path, Files, Paths}
import java.util.{Date, UUID}

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import com.typesafe.config.ConfigFactory
import net.ripe.rpki.publicationserver.store.fs._
import net.ripe.rpki.publicationserver.store.{DeltaStore, Migrations, ObjectStore, ServerStateStore}
import org.h2.store.fs.FileUtils
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration.{Duration, _}

object TestObjects {
  val theDeltaStore = new DeltaStore {
    // set deletion time in the past to see the immediate effect
    override def afterRetainPeriod(period: Duration): Date = new Date(new Date().getTime - 100000)
  }

  val theServerStateStore = new ServerStateStore
  val theObjectStore = new ObjectStore

  val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test_pub_server_")
  rootDir.toFile.deleteOnExit()
}

class TestFSWriter extends FSWriterActor with Config {

  import TestObjects._

  override protected val deltaStore = theDeltaStore
  override protected val objectStore = theObjectStore

  override lazy val conf = new AppConfig {
    override lazy val snapshotRetainPeriod = Duration.Zero
    override lazy val locationRepositoryPath = rootDir.toString
  }
}

class RepositoryStateTest extends PublicationServerBaseTest with ScalatestRouteTest with Hashing {

  import TestObjects._

  private var serial: Long = _

  private var sessionId: UUID = _

  // TODO Remove (of tune) it after debugging
  implicit val customTimeout = RouteTestTimeout(6000.seconds)

  override implicit val system = ActorSystem("MyActorSystem", ConfigFactory.load())

  private val fsWriterRef = TestActorRef[TestFSWriter]

  trait Context {
    def actorRefFactory = system
  }

  def publicationService = {
    val service = new PublicationService with Context {
      override lazy val objectStore = theObjectStore
      override lazy val serverStateStore = theServerStateStore
      override lazy val deltaStore = theDeltaStore
    }
    service.init(fsWriterRef)
    service
  }

  before {
    cleanDir(TestObjects.rootDir.toFile)
    serial = 1L
    theObjectStore.clear()
    theDeltaStore.clear()
    theServerStateStore.clear()
    Migrations.initServerState()
    sessionId = theServerStateStore.get.sessionId
  }

  test("should create snapshots and deltas") {

    val data = Base64("AAAAAA==")
    val publishXml = pubMessage("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer", data)

    val service = publicationService

    // publish, withdraw and re-publish the same object to make
    // delta size larger than snapshot size
    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    // wait until all the actor process their tasks
    waitForActors

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString)) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "1")) should be(false)

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2")) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2", "snapshot.xml")) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2", "delta.xml")) should be(true)
  }

  test("should remove snapshot and delta for serial older than the latest") {

    val data = Base64("AAAAAA==")
    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"
    val publishXml = pubMessage(uri, data)
    val withdrawXml = withdrawMessage(uri, hash(data))

    val service = publicationService

    // publish, withdraw and re-publish the same object to make
    // delta size larger than snapshot size
    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }
    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    // wait until all the actor process their tasks
    waitForActors

    // it should remove deltas 2 and 3 because together with 4th they constitute
    // more than the last snapshot
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString)) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "1")) should be(false)

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2")) should be(false)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2", "snapshot.xml")) should be(false)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2", "delta.xml")) should be(false)

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3")) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3", "snapshot.xml")) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3", "delta.xml")) should be(true)
  }

  test("should schedule deltas for deletion in case their total size is bigger than the size of the request") {

    val data = Base64("AAAAAA==")
    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"
    val publishXml = pubMessage(uri, data)
    val withdrawXml = withdrawMessage(uri, hash(data))

    val service = publicationService

    // publish, withdraw and re-publish the same object to make
    // delta size larger than snapshot size
    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }
    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }
    POST("/?clientId=1234", publishXml.mkString) ~> service.publicationRoutes ~> check { responseAs[String] }

    // wait until all the actor process their tasks
    waitForActors

    // it should remove deltas 2 and 3 because together with 4th they constitute
    // more than the last snapshot
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString)) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "1")) should be(false)

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2")) should be(false)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2", "snapshot.xml")) should be(false)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2", "delta.xml")) should be(false)

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3")) should be(false)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3", "snapshot.xml")) should be(false)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3", "delta.xml")) should be(false)

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "4")) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "4", "snapshot.xml")) should be(true)
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "4", "delta.xml")) should be(true)
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

  def waitForActors = Thread.sleep(3000)

  def checkFileExists(path: Path): Boolean = {
    def checkFile_(path: Path, attempt: Int): Boolean = {
      val e = Files.exists(path)
      if (!e && attempt < 5) {
        Thread.sleep(100 * attempt)
        checkFile_(path, attempt + 1)
      } else
        e
    }
    checkFile_(path, 1)
  }

}
