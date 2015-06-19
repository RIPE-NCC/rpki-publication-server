package net.ripe.rpki.publicationserver.store

import java.util.UUID

import net.ripe.rpki.publicationserver.{Delta, PublicationServerBaseSpec}

class DeltaStoreTest extends PublicationServerBaseSpec {

  val deltaStore = new DeltaStore

  test("should be cool") {
    deltaStore.initCache(UUID.randomUUID())
    deltaStore.getDeltas should have size 0
  }

  test("should be cool 2") {
    val sessionId = UUID.randomUUID()
    val delta = Delta(sessionId.toString, 2L, Seq())
    deltaStore.addDelta(ClientId("client1"), delta)
    val deltas = deltaStore.getDeltas
    deltas should have size 0
  }
}
