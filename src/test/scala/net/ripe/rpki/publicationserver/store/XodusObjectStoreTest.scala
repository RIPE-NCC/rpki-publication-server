package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId

import scala.concurrent.Await
import scala.concurrent.duration._

class XodusObjectStoreTest extends PublicationServerBaseTest with Hashing {

  val objectStore: XodusObjectStore = XodusObjectStore.get

  before {
    objectStore.clear()
  }

  private val uri: URI = new URI("rsync://host.com/path")

  test("should insert an object") {
    val clientId = ClientId("client1")
    val changeSet = QueryMessage(Seq(PublishQ(uri, Some("tag"), hash = None, Base64("AAAA=="))))

    objectStore.applyChanges(changeSet, clientId).onFailure {
      case e => println(e)
    }

    val obj = objectStore.getState.get(uri)
    obj should be(defined)
    obj.get should be((Base64("AAAA=="), hash(Base64("AAAA==")), clientId))
  }

  test("should replace an object") {
    val clientId = ClientId("client1")

    val changeSet = QueryMessage(Seq(PublishQ(uri, tag=None, hash=None, Base64("AAAA=="))))
    Await.result(objectStore.applyChanges(changeSet, clientId), 1.minute)

    val replaceSet = QueryMessage(Seq(PublishQ(uri, tag=None, Some(hash(Base64("AAAA==")).hash), Base64("BBBB=="))))
    Await.result(objectStore.applyChanges(replaceSet, clientId), 1.minute)

    val obj = objectStore.getState.get(uri)
    obj should be(defined)
    obj.get should be((Base64("BBBB=="), hash(Base64("BBBB==")), clientId))
  }

  test("should replace an object in the same message") {
    val clientId = ClientId("client1")

    val changeSet = QueryMessage(Seq(
      PublishQ(uri, tag=None, hash=None, Base64("AAAA==")),
      PublishQ(uri, tag=None, Some(hash(Base64("AAAA==")).hash), Base64("BBBB=="))
    ))
    Await.result(objectStore.applyChanges(changeSet, clientId), 1.minute)

    val obj = objectStore.getState.get(uri)
    obj should be(defined)
    obj.get should be((Base64("BBBB=="), hash(Base64("BBBB==")), clientId))
  }

  test("should store an object, withdraw it and make sure it's not there anymore") {
    val clientId = ClientId("client1")

    val changeSet = QueryMessage(Seq(PublishQ(uri, tag=None, hash=None, Base64("AAAA=="))))
    Await.result(objectStore.applyChanges(changeSet, clientId), 1.minute)

    val withdrawSet = QueryMessage(Seq(WithdrawQ(uri, tag=None, hash(Base64("AAAA==")).hash)))
    Await.result(objectStore.applyChanges(withdrawSet, clientId), 1.minute)

    val obj = objectStore.getState.get(uri)
    obj should not be defined
  }

  test("should store and withdraw object in the same message") {
    val clientId = ClientId("client1")

    Await.result(
      objectStore.applyChanges(
        QueryMessage(Seq(
          PublishQ(uri, tag=None, hash=None, Base64("AAAA==")),
          WithdrawQ(uri, tag=None, hash(Base64("AAAA==")).hash)
        )), clientId), 1.minute)

    val obj = objectStore.getState.get(uri)
    obj should not be defined
  }

}
