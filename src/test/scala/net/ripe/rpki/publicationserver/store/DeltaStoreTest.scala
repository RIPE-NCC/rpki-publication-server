package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.model.{ClientId, Delta}
import net.ripe.rpki.publicationserver.{Base64, PublishQ, PublicationServerBaseTest}

import scala.concurrent.duration.Duration

class DeltaStoreTest extends PublicationServerBaseTest {

  val deltaStore = DeltaStore.get

  before {
    deltaStore.clear()
  }

  test("should be empty when just created") {
    deltaStore.initCache(UUID.randomUUID())
    deltaStore.getDeltas should have size 0
  }

  test("should return what was added") {
    val sessionId = UUID.randomUUID()
    val delta = Delta(sessionId, 2L, Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))
    deltaStore.addDeltaAction(ClientId("client1"), delta)
    val deltas = deltaStore.getDeltas
    deltas should have size 1
    deltas.head should be(delta)
  }

  test("should mark deltas for deletion when their size if bigger than the threshold") {
    val sessionId = UUID.randomUUID()
    val delta1 = Delta(sessionId, 1L, Seq(PublishQ(uri = new URI("rsync://host/zzz1.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))
    deltaStore.addDeltaAction(ClientId("client1"), delta1)

    val delta2 = Delta(sessionId, 2L, Seq(PublishQ(uri = new URI("rsync://host/zzz2.cer"), tag = None, hash = None, base64 = Base64("bbbb="))))
    deltaStore.addDeltaAction(ClientId("client1"), delta2)

    val delta3 = Delta(sessionId, 3L, Seq(PublishQ(uri = new URI("rsync://host/zzz3.cer"), tag = None, hash = None, base64 = Base64("cccc="))))
    deltaStore.addDeltaAction(ClientId("client1"), delta3)

    val (checked, _, _) = deltaStore.markOldestDeltasForDeletion(delta1.binarySize + delta2.binarySize / 2, Duration.Zero)

    checked.head.whenToDelete should be(None)
    checked.head.serial should be(3)
    checked.tail.head.whenToDelete shouldNot be(None)
    checked.tail.head.serial should be(2)
    checked.tail.tail.head.whenToDelete shouldNot be(None)
    checked.tail.tail.head.serial should be(1)

    val deltas = deltaStore.getDeltas
    deltas.head.whenToDelete shouldNot be(None)
    deltas.tail.head.whenToDelete shouldNot be(None)
    deltas.tail.tail.head.whenToDelete should be(None)
  }

  test("should not mark the latest delta for deletion") {
    val sessionId = UUID.randomUUID()
    val delta1 = Delta(sessionId, 1L, Seq(PublishQ(uri = new URI("rsync://host/zzz1.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))
    deltaStore.addDeltaAction(ClientId("client1"), delta1)

    val delta2 = Delta(sessionId, 2L, Seq(PublishQ(uri = new URI("rsync://host/zzz2.cer"), tag = None, hash = None, base64 = Base64("bbbb="))))
    deltaStore.addDeltaAction(ClientId("client1"), delta2)

    val (checked, _, _) = deltaStore.markOldestDeltasForDeletion(delta1.binarySize / 2, Duration.Zero)

    checked.head.whenToDelete should be(None)
    checked.head.serial should be(2)
    checked.tail.head.whenToDelete shouldNot be(None)
    checked.tail.head.serial should be(1)

    val deltas = deltaStore.getDeltas
    deltas.head.whenToDelete shouldNot be(None)
    deltas.tail.head.whenToDelete should be(None)
  }

}
