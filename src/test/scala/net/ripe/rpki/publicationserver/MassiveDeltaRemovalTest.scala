package net.ripe.rpki.publicationserver

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths}
import java.util.{Date, UUID}

import akka.actor.ActorRef
import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.messaging.{Accumulator, RrdpFlusher}
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store._
import net.ripe.rpki.publicationserver.store.fs._
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, Ignore}
import org.scalatest.mock.MockitoSugar

import scala.concurrent.duration._


object MassiveDeltaRemovalTest {

  val timeToRunTheTest: FiniteDuration = 5.seconds
  val deadline = timeToRunTheTest.fromNow
  val deadlineDate: Date = new Date(System.currentTimeMillis() + deadline.timeLeft.toMillis)

  val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test_pub_server_")
  rootDir.toFile.deleteOnExit()
  val rootDirName = rootDir.toString
  val theSessionId = UUID.randomUUID()

  lazy val conf = new AppConfig {
    override lazy val unpublishedFileRetainPeriod = 1.millisecond
    override lazy val snapshotSyncDelay = 1.millisecond
    override lazy val rrdpRepositoryPath = rootDirName
  }

}

@Ignore
class MassiveDeltaRemovalTest extends PublicationServerBaseTest with Hashing with BeforeAndAfterAll with Logging {
  import MassiveDeltaRemovalTest._

  override val waitTime = timeToRunTheTest

  val theRrdpFlusher = TestActorRef(new RrdpFlusher(conf))
  val theStateActor = TestActorRef(new StateActor(conf) {
    override val accActor = TestActorRef(new Accumulator(conf) {
      override lazy val rrdpFlusher = theRrdpFlusher
    })
  })

  def publicationService = new PublicationServiceActor(conf, theStateActor)

  private def sessionDir = findSessionDir(rootDir).toString

  before {
    cleanDir(rootDir.toFile)
  }

  override def afterAll() = {
    cleanDir(rootDir.toFile)
    Files.deleteIfExists(rootDir)
  }


  test("should create snapshots after removing deltas") {

    val data = Bytes.fromBase64(Base64("AAAAAA=="))
    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"

    val service = publicationService

    val clientId: ClientId = ClientId("test")
    val publishQuery = Seq(PublishQ(URI.create(uri), None, None, data))
    val withdrawQuery = Seq(WithdrawQ(URI.create(uri), None, hash(data).hash))

    // publish, withdraw and re-publish the same object as often as we could during the wait period
    while (deadline.hasTimeLeft()) {
      updateState(service, publishQuery, clientId)
      updateState(service, withdrawQuery, clientId)
    }

    val latestSerial = theRrdpFlusher.underlyingActor.currentSerial
    checkFileExists(Paths.get(sessionDir, latestSerial.toString, "snapshot.xml"))

    // this update should trigger removal of all deltas scheduled for deadlineDate
    updateState(service, publishQuery, clientId)

    checkFileAbsent(Paths.get(sessionDir, latestSerial.toString, "delta.xml"))
    checkFileAbsent(Paths.get(sessionDir, latestSerial.toString, "snapshot.xml"))
    checkFileAbsent(Paths.get(sessionDir, latestSerial.toString))

    checkFileExists(Paths.get(sessionDir, (latestSerial + 1).toString, "delta.xml"))
    checkFileExists(Paths.get(sessionDir, (latestSerial + 1).toString, "snapshot.xml"))
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
