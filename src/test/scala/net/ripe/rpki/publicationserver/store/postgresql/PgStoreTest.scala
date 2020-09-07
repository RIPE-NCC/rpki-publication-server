package net.ripe.rpki.publicationserver.store.postgresql

import java.net.URI

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.postresql.RollbackException

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
      uri1 -> (bytes1, hash(bytes1), clientId),
      uri2 -> (bytes2, hash(bytes2), clientId)
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
      uri1 -> (bytes1, hash(bytes1), clientId)
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

    val hash1 = hash(bytes1)
    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, Some(hash1.hash), bytes2))), clientId)

    pgStore.getState should be(Map(
      uri1 -> (bytes2, hash(bytes2), clientId)
    ))

    pgStore.getLog should be(Seq(
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
    pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1),
      PublishQ(uri1, tag = None, Some(hash1.hash), bytes2)
    )), clientId)

    pgStore.getState should be(Map(
      uri1 -> (bytes2, hash(bytes2), clientId)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("UPD", uri1, Some(hash1), Some(bytes2))
    ))
  }

  test("should not replace an object of a different client") {
    val clientId1 = ClientId("client1")
    val clientId2 = ClientId("client2")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(500)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId1)

    try {
      pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, Some(hash(bytes1).hash), bytes2))), clientId2)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("permission_failure")
        e.message should be (s"Not allowed to update an object of another client: [$uri1].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hash(bytes1), clientId1)
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

    val wrongHash = hash(bytes2)
    try {
      pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri2, tag = None, Some(wrongHash.hash), bytes2))), clientId)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("no_object_present")
        e.message should be (s"There is no object present at this URI [$uri2].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hash(bytes1), clientId)
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

    val wrongHash = hash(bytes2)
    try {
      pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, Some(wrongHash.hash), bytes2))), clientId)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("no_object_matching_hash")
        e.message should be (s"Cannot republish the object [$uri1], hash doesn't match, " +
            s"passed ${wrongHash.hash}, but existing one is ${hash(bytes1).hash}")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hash(bytes1), clientId)
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

    val hash1 = hash(bytes1)
    pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash1.hash))), clientId)

    pgStore.getState should be(Map())

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("DEL", uri1, Some(hash1), None)
    ))
  }

  test("should store and withdraw object in the same message") {
    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)

    val hash1 = hash(bytes1)
    pgStore.applyChanges(QueryMessage(Seq(
      PublishQ(uri1, tag = None, hash = None, bytes1),
      WithdrawQ(uri1, tag = None, hash1.hash)
    )), clientId)

    pgStore.getState should be(Map())

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1)),
      ("DEL", uri1, Some(hash1), None)
    ))
  }

  test("should fail to withdraw an object from a different client") {
    val clientId1 = ClientId("client1")
    val clientId2 = ClientId("client2")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val (bytes1, _) = TestBinaries.generateObject(1000)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId1)

    val hash1 = hash(bytes1)
    try {
      pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash1.hash))), clientId2)
      fail()
    } catch {
      case RollbackException(e) =>
          e.code should be ("permission_failure")
          e.message should be (s"Not allowed to delete an object of another client: [$uri1].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hash(bytes1), clientId1)
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

    val hash1 = hash(bytes1)
    try {
      pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri2, tag = None, hash1.hash))), clientId1)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("no_object_present")
        e.message should be (s"There is no object present at this URI [$uri2].")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hash(bytes1), clientId1)
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

    val wrongHash = hash(bytes2)
    try {
      pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, wrongHash.hash))), clientId2)
      fail()
    } catch {
      case RollbackException(e) =>
        e.code should be ("no_object_matching_hash")
        e.message should be (s"Cannot withdraw the object [$uri1], hash does not match, " +
            s"passed ${wrongHash.hash}, but existing one is ${hash(bytes1).hash}.")
    }

    pgStore.getState should be(Map(
      uri1 -> (bytes1, hash(bytes1), clientId1)
    ))

    pgStore.getLog should be(Seq(
      ("INS", uri1, None, Some(bytes1))
    ))
  }

}
