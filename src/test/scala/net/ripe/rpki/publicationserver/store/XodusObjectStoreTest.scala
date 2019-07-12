package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class XodusObjectStoreTest extends PublicationServerBaseTest with Hashing {

  val objectStore: XodusObjectStore = XodusObjectStore.get

  before {
    initStore()
    objectStore.clear()
  }

  private val uri: URI = new URI("rsync://host.com/path")

  test("should insert an object") {
    val clientId = ClientId("client1")
    val changeSet = QueryMessage(Seq(PublishQ(uri, Some("tag"), hash = None, Base64("AAAA=="))))

    Try(objectStore.applyChanges(changeSet, clientId)) match {
      case Failure(e) => println(e)
      case Success(_) => ()
    }

    val obj = objectStore.getState.get(uri)
    obj should be(defined)
    obj.get should be((Base64("AAAA=="), hash(Base64("AAAA==")), clientId))
  }

  test("should replace an object") {
    val clientId = ClientId("client1")

    val changeSet = QueryMessage(Seq(PublishQ(uri, tag=None, hash=None, Base64("AAAA=="))))
    objectStore.applyChanges(changeSet, clientId)

    val replaceSet = QueryMessage(Seq(PublishQ(uri, tag=None, Some(hash(Base64("AAAA==")).hash), Base64("BBBB=="))))
    objectStore.applyChanges(replaceSet, clientId)

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
    objectStore.applyChanges(changeSet, clientId)

    val obj = objectStore.getState.get(uri)
    obj should be(defined)
    obj.get should be((Base64("BBBB=="), hash(Base64("BBBB==")), clientId))
  }

  test("should store an object, withdraw it and make sure it's not there anymore") {
    val clientId = ClientId("client1")

    val changeSet = QueryMessage(Seq(PublishQ(uri, tag=None, hash=None, Base64("AAAA=="))))
    objectStore.applyChanges(changeSet, clientId)

    val withdrawSet = QueryMessage(Seq(WithdrawQ(uri, tag=None, hash(Base64("AAAA==")).hash)))
    objectStore.applyChanges(withdrawSet, clientId)

    val obj = objectStore.getState.get(uri)
    obj should not be defined
  }

  test("should store and withdraw object in the same message") {
    val clientId = ClientId("client1")

    objectStore.applyChanges(
      QueryMessage(Seq(
        PublishQ(uri, tag = None, hash = None, Base64("AAAA==")),
        WithdrawQ(uri, tag = None, hash(Base64("AAAA==")).hash)
      )), clientId)

    val obj = objectStore.getState.get(uri)
    obj should not be defined
  }

}
