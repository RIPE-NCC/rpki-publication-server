package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import java.util.{Date, UUID}

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import akka.testkit.TestKit._
import com.typesafe.config.ConfigFactory
import net.ripe.rpki.publicationserver.store.fs._
import net.ripe.rpki.publicationserver.store.{DeltaStore, Migrations, ObjectStore, ServerStateStore}
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._
import scala.language.postfixOps

object TestObjects {
  val theDeltaStore = new DeltaStore {
    // set deletion time in the past to see the immediate effect
    override def afterRetainPeriod(period: Duration): Date = new Date(0)
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

  val waitTime: FiniteDuration = Duration(30, TimeUnit.SECONDS)

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

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString))

    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "1"))

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2"))
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2", "snapshot.xml"))
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "2", "delta.xml"))
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
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString))
    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "1"))

    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "2"))
    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "2", "snapshot.xml"))
    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "2", "delta.xml"))

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3"))
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3", "snapshot.xml"))
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "3", "delta.xml"))
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
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString))
    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "1"))

    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "2"))
    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "2", "snapshot.xml"))
    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "2", "delta.xml"))

    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "3"))
    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "3", "snapshot.xml"))
    checkFileAbsent(Paths.get(rootDir.toString, sessionId.toString, "3", "delta.xml"))

    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "4"))
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "4", "snapshot.xml"))
    checkFileExists(Paths.get(rootDir.toString, sessionId.toString, "4", "delta.xml"))
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

  def waitForActors = {}

  def checkFileExists(path: Path): Unit = {
    awaitCond(Files.exists(path), max = waitTime)
  }

  def checkFileAbsent(path: Path): Unit = {
    awaitCond(Files.notExists(path), max = waitTime)
  }
}
