package net.ripe.rpki.publicationserver.store.postgresql

import java.net.URI

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.postgresql.RollbackException

class PgStoreTest extends PublicationServerBaseTest with Hashing {

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

    pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1),
      PublishQ(uri2, tag = None, hash = None, bytes2),
    )), clientId)

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId),
      uri2 -> (bytes2, hashOf(bytes2), clientId)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("INS", uri2, None, Some(bytes2))
    ))
  }

  test("should not insert the same object twice") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)
    pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1)
    )), clientId)

    try {
      pgStore.applyChanges(QueryMessage(Seq(
        PublishQ(uri1, tag = None, hash = None, bytes2)
      )), clientId)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("object_already_present")
        e.message should be (s"An object is already present at this URI [$uri1].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1))
    ))
  }

  test("should replace an object") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)

    val hash1 = hashOf(bytes1)
    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, Some(hash1), bytes2))), clientId)

    pgStore.getState should be(Map(
      uri1 -> (bytes2, hashOf(bytes2), clientId)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("UPD", uri1, Some(hash1), Some(bytes2))
    ))
  }

  test("should prevent replacing an object in the same message") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)

    val hash1 = hashOf(bytes1)
    val RollbackException(error) = the [RollbackException] thrownBy pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1),
      PublishQ(uri1, tag = None, Some(hash1), bytes2)
    )), clientId)
    error.code should be ("no_object_present")
    error.message should be (s"There is no object present at this URI [$uri1].")

    pgStore.getState should be(empty)
    pgStore.getLog should be(empty)
  }

  test("should not replace an object of a different client") {
    val clientId1 = ClientId("client1")
    val clientId2 = ClientId("client2")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId1)

    try {
      pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, Some(hashOf(bytes1)), bytes2))), clientId2)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("permission_failure")
        e.message should be (s"Not allowed to replace or withdraw an object of another client: [$uri1].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId1)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1))
    ))
  }


  test("should not replace an object of with a non-existent URI") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val uri2 = new URI(urlPrefix1 + "/path2")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)

    val wrongHash = hashOf(bytes2)
    try {
      pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri2, tag = None, Some(wrongHash), bytes2))), clientId)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("no_object_present")
        e.message should be (s"There is no object present at this URI [$uri2].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1))
    ))
  }

  test("should not replace an object of with a wrong hash") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)

    val wrongHash = hashOf(bytes2)
    try {
      pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, Some(wrongHash), bytes2))), clientId)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("no_object_matching_hash")
        e.message should be (s"Cannot replace or withdraw the object [$uri1], hash does not match, " +
            s"passed ${wrongHash.toHex}, but existing one is ${hashOf(bytes1).toHex}.")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1))
    ))
  }

  test("should store an object, withdraw it and make sure it's not there anymore") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)

    val hash1 = hashOf(bytes1)
    pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash1))), clientId)

    pgStore.getState should be(Map())

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("DEL", uri1, Some(hash1), None)
    ))
  }

  test("should prevent storing and withdrawing an object in the same message") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)

    val hash1 = hashOf(bytes1)
    val RollbackException(error) = the [RollbackException] thrownBy pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1),
      WithdrawQ(uri1, tag = None, hash1)
    )), clientId)
    error.code should be("no_object_present")
    error.message should be(s"There is no object present at this URI [$uri1].")

    pgStore.getState should be(empty)
    pgStore.getLog should be(empty)
  }

  test("should fail to withdraw an object from a different client") {
    val clientId1 = ClientId("client1")
    val clientId2 = ClientId("client2")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId1)

    val hash1 = hashOf(bytes1)
    try {
      pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash1))), clientId2)
      fail()
    } catch {
      case RollbackException(e) =>
          e.code should be ("permission_failure")
          e.message should be (s"Not allowed to replace or withdraw an object of another client: [$uri1].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId1)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1))
    ))
  }


  test("should fail to withdraw an object from a different URI") {
    val clientId1 = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val uri2 = new URI(urlPrefix1 + "/path2")
    val (bytes1, _) = TestBinaries.generateObject(1000)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId1)

    val hash1 = hashOf(bytes1)
    try {
      pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri2, tag = None, hash1))), clientId1)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("no_object_present")
        e.message should be (s"There is no object present at this URI [$uri2].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId1)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1))
    ))
  }

  test("should fail to withdraw an object with wrong hash") {
    val clientId1 = ClientId("client1")
    val clientId2 = ClientId("client2")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(1000)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId1)

    val wrongHash = hashOf(bytes2)
    try {
      pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, wrongHash))), clientId2)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("no_object_matching_hash")
        e.message should be (s"Cannot replace or withdraw the object [$uri1], hash does not match, " +
            s"passed ${wrongHash.toHex}, but existing one is ${hashOf(bytes1).toHex}.")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId1)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1))
    ))
  }

  test("should allow same object to be published in multiple locations") {
    val clientId = ClientId("client")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val uri2 = new URI(urlPrefix1 + "/path2")
    val (bytes1, _) = TestBinaries.generateObject(10)

    pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1),
      PublishQ(uri2, tag = None, hash = None, bytes1),
    )), clientId)

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId),
      uri2 -> (bytes1, hashOf(bytes1), clientId)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("INS", uri2, None, Some(bytes1)),
    ))

    pgStore.applyChanges(QueryMessage(Seq(
      WithdrawQ(uri1, tag = None, hash = hashOf(bytes1))
    )), clientId)

    pgStore.getState should be(Map(
      uri2 -> (bytes1, hashOf(bytes1), clientId)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("INS", uri2, None, Some(bytes1)),
      ("DEL", uri1, Some(hashOf(bytes1)), None),
    ))
  }

  test("should update client id when another client publishes object at location of withdrawn object") {
    val clientId1 = ClientId("client1")
    val clientId2 = ClientId("client2")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val uri2 = new URI(urlPrefix1 + "/path2")
    val (bytes1, _) = TestBinaries.generateObject(10)

    pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1),
    )), clientId1)
    pgStore.applyChanges(QueryMessage(Seq(
      WithdrawQ(uri1, tag = None, hash = hashOf(bytes1)),
    )), clientId1)

    pgStore.getState should be(empty)

    // Replace withdrawn object by another client
    pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1),
    )), clientId2)

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hashOf(bytes1), clientId2)
    ))
    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("DEL", uri1, Some(hashOf(bytes1)), None),
      ("INS", uri1, None, Some(bytes1)),
    ))
  }

}