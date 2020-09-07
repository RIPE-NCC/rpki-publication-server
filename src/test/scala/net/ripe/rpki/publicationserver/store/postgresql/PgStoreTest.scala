package net.ripe.rpki.publicationserver.store.postgresql

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId

import scala.util.{Failure, Success, Try}

class PgStoreTest extends PublicationServerBaseTest with Hashing {

  private val uri: URI = new URI("rsync://host.com/path")

  val urlPrefix1 = "rsync://host1.com"

  val pgStore = createPgStore

  before {
    pgStore.clear()
  }

  test("should insert a couple of objects") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val uri2 = new URI(urlPrefix1 + "/path2")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)
    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, bytes1),
      PublishQ(uri2, tag=None, hash=None, bytes2),
    ))
    pgStore.applyChanges(changeSet, clientId)

    val state = pgStore.getState

    state should be(Map(
      uri1 -> (bytes1, hash(bytes1), clientId),
      uri2 -> (bytes2, hash(bytes2), clientId)
    ))

    val log = pgStore.getLog
    log should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("INS", uri2, None, Some(bytes2))
    ))
  }

  test("should replace an object") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)

    val changeSet = QueryMessage(Seq(PublishQ(uri1, tag=None, hash=None, bytes1)))
    pgStore.applyChanges(changeSet, clientId)

    val hash1 = hash(bytes1)
    val replaceSet = QueryMessage(Seq(PublishQ(uri1, tag=None, Some(hash1.hash), bytes2)))
    pgStore.applyChanges(replaceSet, clientId)

    val state = pgStore.getState

    state should be(Map(
      uri1 -> (bytes2, hash(bytes2), clientId)
    ))

    val log = pgStore.getLog
    log should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("UPD", uri1, Some(hash1), Some(bytes2))
    ))
  }

  test("should replace an object in the same message") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)

    val hash1 = hash(bytes1)
    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, bytes1),
      PublishQ(uri1, tag=None, Some(hash1.hash), bytes2)
    ))

    pgStore.applyChanges(changeSet, clientId)
    val state = pgStore.getState

    state should be(Map(
      uri1 -> (bytes2, hash(bytes2), clientId)
    ))

    val log = pgStore.getLog
    log should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("UPD", uri1, Some(hash1), Some(bytes2))
    ))
  }

  test("should store an object, withdraw it and make sure it's not there anymore") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)

    val changeSet = QueryMessage(Seq(PublishQ(uri1, tag=None, hash=None, bytes1)))
    pgStore.applyChanges(changeSet, clientId)

    val hash1 = hash(bytes1)
    val withdrawSet = QueryMessage(Seq(WithdrawQ(uri1, tag=None, hash1.hash)))
    pgStore.applyChanges(withdrawSet, clientId)

    val state = pgStore.getState
    state should be(Map())

    val log = pgStore.getLog
    log should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("DEL", uri1, Some(hash1), None)
    ))
  }

  test("should store and withdraw object in the same message") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)

    val hash1 = hash(bytes1)
    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, bytes1),
      WithdrawQ(uri1, tag=None, hash1.hash)
    ))
    pgStore.applyChanges(changeSet, clientId)

    val state = pgStore.getState
    state should be(Map())

    val log = pgStore.getLog
    log should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("DEL", uri1, Some(hash1), None)
    ))
  }

}
