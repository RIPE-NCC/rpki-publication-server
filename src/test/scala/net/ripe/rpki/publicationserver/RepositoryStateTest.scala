package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Files, Paths}
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.fs._
import net.ripe.rpki.publicationserver.store.{DeltaStore, Migrations, ObjectStore, ServerStateStore}
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.util.Try

class RepositoryStateTest extends PublicationServerBaseTest with ScalatestRouteTest with Hashing {

  private var serial: Long = _

  private var sessionId: UUID = _

  private val theDeltaStore = DeltaStore.get

  private val theServerStateStore = new ServerStateStore

  private val theObjectStore = new ObjectStore

  // TODO Remove (of tune) it after debugging
  implicit val customTimeout = RouteTestTimeout(6000.seconds)

  override implicit val system = ActorSystem("MyActorSystem", ConfigFactory.load())
  
  private val fsWriterRef = TestActorRef[FSWriterActor]

  val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test")

  val conf_ = new AppConfig {
    override lazy val snapshotRetainPeriod = Duration.Zero
    override lazy val locationRepositoryPath = rootDir.toString
  }

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
    serial = 1L
    theObjectStore.clear()
    theDeltaStore.clear()
    theServerStateStore.clear()
    Migrations.initServerState()
    sessionId = theServerStateStore.get.sessionId
  }

  test("should schedule deltas for deletion in case their total size is bigger than the size of the request") {
    val publishXml = getFile("/publish.xml")
    val publishXmlResponse = getFile("/publishResponse.xml")

    POST("/?clientId=1234", publishXml.mkString) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishXmlResponse.mkString))
    }
  }
  

  def getRepositoryWriter = new MockRepositoryWriter()

  class MockRepositoryWriter extends RepositoryWriter {
    override def writeSnapshot(rootDir: String, serverState: ServerState, snapshot: Snapshot) = Paths.get("")
    override def writeDelta(rootDir: String, delta: Delta) = Try(Paths.get(""))
    override def writeNotification(rootDir: String, notification: Notification) = None
  }

}
