package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Files, Paths}
import java.util.{Date, UUID}

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import net.ripe.rpki.publicationserver.Config
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.fs._
import net.ripe.rpki.publicationserver.store.{DeltaStore, Migrations, ObjectStore, ServerStateStore}
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.util.Try

object TestObjects {
  val theDeltaStore = new DeltaStore {
    // set deletion time in the past to see the immediate effect
    override def afterRetainPeriod(period: Duration): Date = new Date(new Date().getTime - 1)
  }

  val theServerStateStore = new ServerStateStore
  val theObjectStore = new ObjectStore

}

class TestFSWriter extends FSWriterActor with Config {

  import TestObjects._

  private val deltaStore = theDeltaStore
  private val objectStore = theObjectStore

  lazy val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test")

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
    serial = 1L
    theObjectStore.clear()
    theDeltaStore.clear()
    theServerStateStore.clear()
    Migrations.initServerState()
    sessionId = theServerStateStore.get.sessionId
  }

  test("should schedule deltas for deletion in case their total size is bigger than the size of the request") {

    val data = Base64("AAAAAA==")
    val dHash = hash(data)

    val publishXml = s"""<msg
        type="query"
        version="3"
        xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
          <publish
          uri="rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer">${data.value}</publish>
        </msg>"""

    val withdrawXml = s"""<msg
                            type="query"
                            version="3"
                            xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
                          <withdraw
                              hash="${dHash.hash}"
                              uri="rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"/>
                        </msg>"""

    // publish, withdraw and re-publish the same object to make
    // delta size larger than snapshot size
    POST("/?clientId=1234", publishXml.mkString) ~> publicationService.publicationRoutes ~> check { responseAs[String] }
    POST("/?clientId=1234", withdrawXml.mkString) ~> publicationService.publicationRoutes ~> check { responseAs[String] }
    POST("/?clientId=1234", publishXml.mkString) ~> publicationService.publicationRoutes ~> check { responseAs[String] }


  }


  def getRepositoryWriter = new MockRepositoryWriter()

  class MockRepositoryWriter extends RepositoryWriter {
    override def writeSnapshot(rootDir: String, serverState: ServerState, snapshot: Snapshot) = Paths.get("")
    override def writeDelta(rootDir: String, delta: Delta) = Try(Paths.get(""))
    override def writeNotification(rootDir: String, notification: Notification) = None
  }

}
