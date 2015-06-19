package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.{Base64, PublishQ, Delta, PublicationServerBaseSpec}

class DeltaStoreSpec extends PublicationServerBaseSpec {

  val deltaStore = new DeltaStore

  before {
    Migrations.migrate
  }

  test("should be empty when just created") {
    deltaStore.initCache(UUID.randomUUID())
    deltaStore.getDeltas should have size 0
  }

  test("should return what was added") {
    val sessionId = UUID.randomUUID()
    val delta = Delta(sessionId.toString, 2L, Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))
    deltaStore.addDelta(ClientId("client1"), delta)
    val deltas = deltaStore.getDeltas
    deltas should have size 1
    deltas.head should be(delta)
  }

}
