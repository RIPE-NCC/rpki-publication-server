package net.ripe.rpki.publicationserver

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.Date

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import akka.testkit.TestKit._
import com.typesafe.config.ConfigFactory
import net.ripe.rpki.publicationserver.model.{Delta, ClientId}
import net.ripe.rpki.publicationserver.store.fs._
import net.ripe.rpki.publicationserver.store._
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mock.MockitoSugar

import scala.concurrent.duration._
import scala.util.Try


object MassiveDeltaRemovalTest {
  import scala.language.postfixOps

  val timeToRunTheTest: FiniteDuration = 30 seconds
  val deadline = timeToRunTheTest.fromNow
  val deadlineDate: Date = new Date(System.currentTimeMillis() + deadline.timeLeft.toMillis)

  val theUpdateStore = new UpdateStore {
    // for test, override it with constant time at deadline
    override def afterRetainPeriod(period: Duration) = deadlineDate
  }

  val theServerStateStore = new ServerStateStore
  val theObjectStore = new ObjectStore

  val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test_pub_server_")
  rootDir.toFile.deleteOnExit()
  val rootDirName = rootDir.toString
  val theRsyncWriter = MockitoSugar.mock[RsyncRepositoryWriter]

  class TestFSWriter extends FSWriterActor with Config with MockitoSugar {
    override protected val updateStore = theUpdateStore
    override protected val objectStore = theObjectStore
    override lazy val rsyncWriter = theRsyncWriter

    override lazy val conf = new AppConfig {
      override lazy val unpublishedFileRetainPeriod = Duration.Zero
      override lazy val rrdpRepositoryPath = rootDirName
    }
  }
}


class MassiveDeltaRemovalTest extends PublicationServerBaseTest with Hashing with BeforeAndAfterAll with Logging {
  import MassiveDeltaRemovalTest._

  private var sessionDir: String = _

  implicit val system = ActorSystem("DeltaRemovalTestActorSystem", ConfigFactory.load())

  private val fsWriterRef = TestActorRef[TestFSWriter]

  trait Context {
    def actorRefFactory = system
  }

  def snapshotStateService: SnapshotStateService = {
    val service = new SnapshotStateService {
      override lazy val objectStore = theObjectStore
      override lazy val serverStateStore = theServerStateStore
      override lazy val updateStore = theUpdateStore
    }
    service.init(fsWriterRef)
    service
  }

  before {
    cleanDir(rootDir.toFile)
    theObjectStore.clear()
    theUpdateStore.clear()
    theServerStateStore.clear()
    Migrations.initServerState()
    sessionDir = rootDir.resolve(theServerStateStore.get.sessionId.toString).toString
    when(MassiveDeltaRemovalTest.theRsyncWriter.writeDelta(any[Delta])).thenReturn(Try {})
  }

  override def afterAll() = {
    cleanDir(rootDir.toFile)
    Files.deleteIfExists(rootDir)
  }

  test("should create snapshots after removing deltas") {

    val data = Base64("AAAAAA==")
    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"

    val service = snapshotStateService

    val clientId: ClientId = ClientId("test")
    val publishQuery: Seq[QueryPdu] = Seq(PublishQ(URI.create(uri), None, None, data))
    val withdrawQuery: Seq[QueryPdu] = Seq(WithdrawQ(URI.create(uri), None, hash(data).hash))

    var expectedSerial: Int = 1
    checkFileExists(Paths.get(sessionDir, String.valueOf(expectedSerial), "snapshot.xml"))

    // publish, withdraw and re-publish the same object as often as we could during the wait period
    while (deadline.hasTimeLeft()) {
      service.updateWith(clientId, publishQuery)
      service.updateWith(clientId, withdrawQuery)
      expectedSerial += 2
    }
    logger.info("Expected serial: {}", expectedSerial)

    checkFileExists(Paths.get(sessionDir, String.valueOf(expectedSerial), "delta.xml"))
    checkFileExists(Paths.get(sessionDir, String.valueOf(expectedSerial), "snapshot.xml"))

    // this update should trigger removal of all deltas scheduled for deadlineDate
    service.updateWith(clientId, publishQuery)

    checkFileExists(Paths.get(sessionDir, String.valueOf(expectedSerial+1), "delta.xml"))
    checkFileExists(Paths.get(sessionDir, String.valueOf(expectedSerial+1), "snapshot.xml"))

    // check cleanup job
    checkFileAbsent(Paths.get(sessionDir, String.valueOf(expectedSerial), "snapshot.xml"))
    (1 to expectedSerial-1) foreach { serial =>
      checkFileAbsent(Paths.get(sessionDir, String.valueOf(serial), "snapshot.xml"))
    }

    // and after that everything should work correctly
    service.updateWith(clientId, withdrawQuery)
    checkFileExists(Paths.get(sessionDir, String.valueOf(expectedSerial+2), "delta.xml"))
    checkFileExists(Paths.get(sessionDir, String.valueOf(expectedSerial+2), "snapshot.xml"))
  }

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

  def checkFileExists(path: Path): Unit = {
    awaitCond(Files.exists(path), max = timeToRunTheTest)
  }

  def checkFileAbsent(path: Path): Unit = {
    awaitCond(Files.notExists(path), max = timeToRunTheTest)
  }
}
