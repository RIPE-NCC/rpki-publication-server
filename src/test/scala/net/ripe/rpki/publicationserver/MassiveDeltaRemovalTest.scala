package net.ripe.rpki.publicationserver

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths}
import java.util.{Date, UUID}

import akka.testkit.{TestActorRef, TestKit}
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.messaging.{Accumulator, RrdpFlusher, RrdpCleaner}
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model.ClientId
import org.scalatest.{BeforeAndAfterAll, Ignore}

import scala.concurrent.duration._
import scala.concurrent.Await


object MassiveDeltaRemovalTest {
  val timeToRunTheTest: FiniteDuration = 5.seconds
  val deadline = timeToRunTheTest.fromNow
  val deadlineDate: Date = new Date(System.currentTimeMillis() + deadline.timeLeft.toMillis)

  val rootDir = Files.createTempDirectory("test_massive_delta_removal_")

  val rootDirName = rootDir.toAbsolutePath.toString
  val theSessionId = UUID.randomUUID()

  lazy val conf = new AppConfig {
    override lazy val unpublishedFileRetainPeriod = 1.second
    override lazy val snapshotSyncDelay = 1.second
    override lazy val rrdpRepositoryPath = rootDirName
  }

}


class MassiveDeltaRemovalTest extends PublicationServerBaseTest with Hashing with BeforeAndAfterAll with Logging {
  import MassiveDeltaRemovalTest._

  lazy val theRrdpCleaner = TestActorRef(new RrdpCleaner(conf))
  lazy val theRrdpFlusher = TestActorRef(new RrdpFlusher(conf){
    override val rrdpCleaner = theRrdpCleaner;
  })
  lazy val accumulateActor = TestActorRef(new Accumulator(conf) { 
    override val rrdpFlusher = theRrdpFlusher })

  lazy val theStateActor = TestActorRef(new StateActor(conf, mock[Metrics]) {
    override val accActor = accumulateActor  })

  lazy val publicationService = new PublicationService(conf, theStateActor)

  private def sessionDir = findSessionDir(rootDir).toString

  override def beforeAll() {
    conf.rsyncRepositoryMapping.foreach(z => cleanDir(z._2))           
    initStore("massive")
  }

  override def afterAll(): Unit = {
    cleanStore()
    cleanUp()
    cleanDir(rootDir)
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
     checkFileExists(Paths.get(sessionDir, latestSerial.toString, "snapshot.xml"))

     // this update should trigger removal of all deltas scheduled for deadlineDate
     updateState(service, publishQuery, clientId)

     checkFileAbsent(Paths.get(sessionDir, latestSerial.toString, "delta.xml"))
     checkFileAbsent(Paths.get(sessionDir, latestSerial.toString, "snapshot.xml"))
     //checkFileAbsent(Paths.get(sessionDir, latestSerial.toString))

     checkFileExists(Paths.get(sessionDir, (latestSerial + 1).toString, "delta.xml"))
     checkFileExists(Paths.get(sessionDir, (latestSerial + 1).toString, "snapshot.xml")) 
 
   }

}
