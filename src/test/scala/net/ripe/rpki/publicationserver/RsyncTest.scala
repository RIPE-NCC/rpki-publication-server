package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{FileSystems, Files}
import java.util.UUID

import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.model.ServerState
import net.ripe.rpki.publicationserver.store.ObjectStore
import org.apache.commons.io.FileUtils
import org.scalatest.Ignore
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._

class RsyncTest extends PublicationServerBaseTest with ScalatestRouteTest {

  val rsyncDir = "/tmp/a"

  val conf = new AppConfig {
    override lazy val snapshotSyncDelay = 1.millisecond
  }

  def theStateActor = TestActorRef(new StateActor(conf))
  def publicationService = TestActorRef(new PublicationServiceActor(conf) {
    override lazy val stateActor = theStateActor
  }).underlyingActor


  before {
    ObjectStore.get.clear()
    val tmpDir = new File(rsyncDir)
    if (tmpDir.exists()) {
      FileUtils.deleteDirectory(tmpDir)
    }
  }

  test("should publish the contents in the publication request to the correct rsync folder") {
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)

      // The publish.xml request contains a certificate with uri rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer
      // The application.conf file in test/resources contains a mapping from the prefix rsync://wombat.example to the
      // filesystem location /tmp/a
      // So the filesystem location where the rsyncRepositoryWriter should publish the file is /tmp/a + /online + /Alice/blCrcCp9ltyPDNzYKPfxc.cer
      checkFileExists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    }
  }


  test("should write the snapshot and delta's from the db to the filesystem on init") {
    // trigger InitRepo message sent to Accumulator and RsyncFlusher
    theStateActor.underlyingActor.preStart()

    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }
    Thread.sleep(1000)

    // The tmp/a directory is removed in the 'before' method, but the database still contains the object published by the previous test.
    checkFileExists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
  }

  test("should fail writing rsync and clean up for itself on init") {
    // reinitialize in-memory state
    theStateActor.underlyingActor.preStart()

    //
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }
    Thread.sleep(1000)


    POST("/?clientId=1234", getFile("/publish_nonexistent_uri.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)

      // The publish_nonexistent_uri.xml request contains a certificate with uri
      // rsync://nonexistent.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer
      // The application.conf file in test/resources contains a mapping from the
      // prefix rsync://nonexistent.example to the non-existing filesystem location /nonexistent
      // So the object writing should fail

      checkFileExists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
      checkFileAbsent(FileSystems.getDefault.getPath(s"/nonexistent/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    }
  }
}
