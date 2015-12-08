package net.ripe.rpki.publicationserver

import java.nio.file.{FileSystems, Files}
import java.util.UUID

import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.model.Delta
import net.ripe.rpki.publicationserver.store.DeltaStore
import net.ripe.rpki.publicationserver.store.fs.FSWriterActor
import spray.testkit.ScalatestRouteTest
import org.mockito.Matchers._
import org.mockito.Mockito._

class RsyncTest extends PublicationServerBaseTest with ScalatestRouteTest {

  val config = AppConfig
  val fsWriterRef = TestActorRef[FSWriterActor]
  def actorRefFactory = system

  trait Context {
    def actorRefFactory = system
  }

  def publicationService = {
    val service = new PublicationService with Context
    service.init(fsWriterRef)
    service
  }

  test("should publish the contents in the publication request to the correct rsync folder") {
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)

      // The publish.xml request contains a certificate with uri rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer
      // The reference.conf file in test/resources contains a mapping from the prefix rsync://wombat.example to the filesystem location /tmp/a
      // So the filesystem location where the rsyncRepositoryWriter should publish the file is /tmp/a + /Alice/blCrcCp9ltyPDNzYKPfxc.cer

      Files.exists(FileSystems.getDefault.getPath("/tmp/a/Alice/blCrcCp9ltyPDNzYKPfxc.cer")) should be(true)
    }
  }

  test("should write the snapshot and delta's from the db to the filesystem on init") {
    val sessionId = UUID.randomUUID()
    val mockDeltaStore = spy(new DeltaStore)

    when(mockDeltaStore.getDeltas).thenReturn(Seq(Delta(sessionId, 1L, Seq.empty)))

    // TODO instantiate rsyncRepositoryWriter with mocked stores and stuff and call init

    verify(mockDeltaStore).initCache(any[UUID])
  }
}
