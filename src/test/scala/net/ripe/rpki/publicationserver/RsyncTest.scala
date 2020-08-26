package net.ripe.rpki.publicationserver

import java.io.File
import java.net.URI
import java.nio.file.FileSystems

import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.model.ClientId
import org.apache.commons.io.FileUtils
import akka.http.scaladsl.testkit.ScalatestRouteTest
import net.ripe.rpki.publicationserver.store.postresql.PgStore

import scala.concurrent.duration._

class RsyncTest extends PublicationServerBaseTest with ScalatestRouteTest with Hashing {

  val rsyncDir = "/tmp/a"
  val clientId = "1234"

  val conf = new AppConfig {
    override lazy val snapshotSyncDelay = 1.second
  }

   def theStateActor = TestActorRef(new StateActor(conf, testMetrics))
   def publicationService = new PublicationService(conf, theStateActor)

  private val objectStore = PgStore.get(pgTestConfig)

  before {
    objectStore.clear()
    val tmpDir = new File(rsyncDir)
    if (tmpDir.exists()) {
      FileUtils.deleteDirectory(tmpDir)
    }
  }

  test("should publish the contents in the publication request to the correct rsync folder") {
    POST(s"/?clientId=$clientId", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)

      // The publish.xml request contains a certificate with uri rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer
      // The application.conf file in test/resources contains a mapping from the prefix rsync://wombat.example to the
      // filesystem location /tmp/a
      // So the filesystem location where the rsyncRepositoryWriter should publish the file is /tmp/a + /online + /Alice/blCrcCp9ltyPDNzYKPfxc.cer
      checkFileExists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    }
  }

  test("should delete the object when getting withdraw request") {
    val service  = publicationService
    val bytes = Bytes.fromBase64(Base64("DEADBEEF"))
    updateStateWithCallback(service, Seq(PublishQ(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"), None, None, bytes)), ClientId(clientId)) {
      checkFileExists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    }

    updateStateWithCallback(service, Seq(WithdrawQ(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"), tag = Some("123"), hash(bytes).hash)), ClientId(clientId)) {
      checkFileAbsent(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    }
  }


  test("should write the snapshot and delta's from the db to the filesystem on init") {
    // trigger InitRepo message sent to Accumulator and RsyncFlusher
    theStateActor.underlyingActor.preStart()

    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }

    // The tmp/a directory is removed in the 'before' method, but the database still contains the object published by the previous test.
    checkFileExists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
  }

}
