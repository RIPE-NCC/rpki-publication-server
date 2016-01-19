package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{FileSystems, Files}
import java.util.UUID

import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.model.ServerState
import net.ripe.rpki.publicationserver.store.ObjectStore
import org.apache.commons.io.FileUtils
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._

class RsyncTest extends PublicationServerBaseTest with ScalatestRouteTest {

  val rsyncDir = "/tmp/a"

  val config = new AppConfig {
    override lazy val snapshotSyncDelay = 1.millisecond
  }

  def actorRefFactory = system

  def publicationService = TestActorRef(new PublicationServiceActor(config)).underlyingActor

  before {
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

      FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer").toFile should exist
    }
  }

  test("should write the snapshot and delta's from the db to the filesystem on init") {
    val sessionId = UUID.randomUUID()
    val newServerState = ServerState(sessionId, 123L)

    // The tmp/a directory is removed in the 'before' method, but the database still contains the object published by the previous test.
    // So initFSContent should find the object in the database and write it to rsync

    Files.exists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer")) should be(true)
  }

  test("should fail writing rsync and clean up for itself on init") {
    ObjectStore.get.clear()
    POST("/?clientId=1234", getFile("/publish_nonexistent_uri.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)

      // The publish_nonexistent_uri.xml request contains a certificate with uri
      // rsync://nonexistent.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer
      // The application.conf file in test/resources contains a mapping from the
      // prefix rsync://nonexistent.example to the non-existing filesystem location /nonexistent
      // So the object writing should fail

      !Files.exists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer")) should be(true)
      !Files.exists(FileSystems.getDefault.getPath(s"/nonexistent/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer")) should be(true)
    }
  }
}
