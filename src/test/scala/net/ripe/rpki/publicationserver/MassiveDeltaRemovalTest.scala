package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Files, Paths}
import java.util.{Date, UUID}

import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.messaging._
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration._

object MassiveDeltaRemovalTest {

}

class MassiveDeltaRemovalTest
    extends PublicationServerBaseTest
    with Hashing
    with BeforeAndAfterAll
    with Logging {

  private val timeToRunTheTest: FiniteDuration = 5.seconds
  private val deadline = timeToRunTheTest.fromNow

  private val rootDir = Files.createTempDirectory("test_massive_delta_removal_")
  private val rootDirName = rootDir.toAbsolutePath.toString

  private lazy val conf = new AppConfig {
    override lazy val unpublishedFileRetainPeriod = 1.second
    override lazy val snapshotSyncDelay = 1.second
    override lazy val rrdpRepositoryPath = rootDirName
    override lazy val pgConfig = pgTestConfig
  }

  lazy val theRrdpCleaner = TestActorRef(new RrdpCleaner(conf))
  lazy val theRrdpFlusher = TestActorRef(new RrdpFlusher(conf) {
    override val rrdpCleaner = theRrdpCleaner;
  })
  lazy val accumulateActor = TestActorRef(new Accumulator(conf) {
    override val rrdpFlusher = theRrdpFlusher
  })

  lazy val theStateActor = TestActorRef(new StateActor(conf, mock[Metrics]) {
    override val accActor = accumulateActor
  })

  lazy val publicationService = new PublicationService(conf, theStateActor)

  private def sessionDir = findSessionDir(rootDir).toString

  private val objectStore = PgStore.get(pgTestConfig)

  override def beforeAll() {
    conf.rsyncRepositoryMapping.foreach(z => cleanDir(z._2))
    objectStore.clear()
  }

  test("should create snapshots after removing deltas") {
    val data = Bytes.fromBase64(Base64("AAAAAA=="))
    val uri = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"

    val service = publicationService

    val clientId: ClientId = ClientId("test_massive")
    val publishQuery = Seq(PublishQ(URI.create(uri), None, None, data))
    val withdrawQuery = Seq(WithdrawQ(URI.create(uri), None, hash(data).hash))

    // publish, withdraw and re-publish the same object as often as we could during the wait period
    while (deadline.hasTimeLeft()) {
      updateState(service, publishQuery, clientId)
      updateState(service, withdrawQuery, clientId)
    }

    val latestSerial = theRrdpFlusher.underlyingActor.currentSerial
    checkFileExists(
      Paths.get(sessionDir, latestSerial.toString, "snapshot.xml")
    )

    // this update should trigger removal of all deltas scheduled for deadlineDate
    updateState(service, publishQuery, clientId)

    checkFileAbsent(Paths.get(sessionDir, latestSerial.toString, "delta.xml"))
    checkFileAbsent(
      Paths.get(sessionDir, latestSerial.toString, "snapshot.xml")
    )
    //checkFileAbsent(Paths.get(sessionDir, latestSerial.toString))

    checkFileExists(
      Paths.get(sessionDir, (latestSerial + 1).toString, "delta.xml")
    )
    checkFileExists(
      Paths.get(sessionDir, (latestSerial + 1).toString, "snapshot.xml")
    )

  }

}
