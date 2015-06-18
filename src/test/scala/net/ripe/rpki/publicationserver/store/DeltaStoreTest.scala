package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.{Delta, Hash, Base64, PublicationServerBaseSpec}
import org.scalatest.FunSuite

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class DeltaStoreTest extends PublicationServerBaseSpec {

  val db = DB.inMemory
  val deltaStore = new DeltaStore(db)

  before {
    Migrations.migrate(db)
  }

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
