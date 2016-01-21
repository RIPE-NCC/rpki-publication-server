package net.ripe.rpki.publicationserver

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths}
import java.util.{Date, UUID}

import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.messaging.RrdpFlusher
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store._
import net.ripe.rpki.publicationserver.store.fs._
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mock.MockitoSugar

import scala.concurrent.duration._


object MassiveDeltaRemovalTest {

  val timeToRunTheTest: FiniteDuration = 30.seconds
  val deadline = timeToRunTheTest.fromNow
  val deadlineDate: Date = new Date(System.currentTimeMillis() + deadline.timeLeft.toMillis)

  val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test_pub_server_")
  rootDir.toFile.deleteOnExit()
  val rootDirName = rootDir.toString
  val theRsyncWriter = MockitoSugar.mock[RsyncRepositoryWriter]
  val theSessionId = UUID.randomUUID()

  lazy val conf = new AppConfig {
    override lazy val unpublishedFileRetainPeriod = 1.millisecond
    override lazy val snapshotSyncDelay = 1.millisecond
    override lazy val rrdpRepositoryPath = rootDirName
  }

}


class MassiveDeltaRemovalTest extends PublicationServerBaseTest with Hashing with BeforeAndAfterAll with Logging {
  import MassiveDeltaRemovalTest._

  override val waitTime = timeToRunTheTest

  def publicationService = TestActorRef(new PublicationServiceActor(conf)).underlyingActor

  private def sessionDir = findSessionDir(rootDir).toString

  before {
    cleanDir(rootDir.toFile)
  }

  override def afterAll() = {
    cleanDir(rootDir.toFile)
    Files.deleteIfExists(rootDir)
  }


  test("should create snapshots after removing deltas") {

    val data = Base64("AAAAAA==")
    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"

    val service = publicationService

    val clientId: ClientId = ClientId("test")
    val publishQuery = Seq(PublishQ(URI.create(uri), None, None, data))
    val withdrawQuery = Seq(WithdrawQ(URI.create(uri), None, hash(data).hash))

    var expectedSerial: Int = 1
    checkFileExists(Paths.get(sessionDir, String.valueOf(expectedSerial), "snapshot.xml"))

    // publish, withdraw and re-publish the same object as often as we could during the wait period
    while (deadline.hasTimeLeft()) {
      updateState(service, publishQuery, clientId)
      updateState(service, withdrawQuery, clientId)
    }
    logger.info("Expected serial: {}", expectedSerial)

    checkFileExists(Paths.get(sessionDir, "1", "delta.xml"))
    checkFileExists(Paths.get(sessionDir, "1", "snapshot.xml"))

    // this update should trigger removal of all deltas scheduled for deadlineDate
    updateState(service, publishQuery, clientId)

    checkFileExists(Paths.get(sessionDir, "1", "delta.xml"))
    checkFileExists(Paths.get(sessionDir, "1", "snapshot.xml"))

    // check cleanup job
    checkFileAbsent(Paths.get(sessionDir, String.valueOf(expectedSerial), "snapshot.xml"))
    1 until expectedSerial foreach { serial =>
      checkFileAbsent(Paths.get(sessionDir, String.valueOf(serial), "snapshot.xml"))
    }

    // and after that everything should work correctly
    updateState(service, withdrawQuery, clientId)
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
}
