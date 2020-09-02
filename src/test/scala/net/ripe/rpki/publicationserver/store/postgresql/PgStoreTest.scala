package net.ripe.rpki.publicationserver.store.postgresql

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import net.ripe.rpki.publicationserver._

import scala.util.{Failure, Success, Try}

class PgStoreTest extends PublicationServerBaseTest with Hashing {

  private val uri: URI = new URI("rsync://host.com/path")

  val objectStore = PgStore.get(pgTestConfig)

  before {
    objectStore.clear()
  }

  test("should insert an object") {
    val clientId = ClientId("client1")
    val bytes = Bytes(Array(0x01, 0x02, 0x03))
    val changeSet = QueryMessage(Seq(PublishQ(uri, Some("tag"), hash = None, bytes)))

    Try(objectStore.applyChanges(changeSet, clientId)) match {
      case Failure(e) => println(e)
      case Success(_) => ()
    }

    val obj = objectStore.getState.get(uri)
    obj should be(defined)
    obj.get should be((bytes, hash(bytes), clientId))
  }

  test("should replace an object") {
    val clientId = ClientId("client1")

    val bytesA = Bytes.fromBase64(Base64("AABBCC=="))
    val bytesB = Bytes.fromBase64(Base64("BBBBAA=="))
    val changeSet = QueryMessage(Seq(PublishQ(uri, tag=None, hash=None, bytesA)))
    objectStore.applyChanges(changeSet, clientId)

    val replaceSet = QueryMessage(Seq(PublishQ(uri, tag=None, Some(hash(bytesA).hash), bytesB)))
    objectStore.applyChanges(replaceSet, clientId)

    val obj = objectStore.getState.get(uri)
    obj should be(defined)
    obj.get should be((bytesB, hash(bytesB), clientId))
  }

  test("should replace an object in the same message") {
    val clientId = ClientId("client1")

    val bytesA = Bytes.fromBase64(Base64("AABBCC=="))
    val bytesB = Bytes.fromBase64(Base64("BBBBAA=="))

    val changeSet = QueryMessage(Seq(
      PublishQ(uri, tag=None, hash=None, bytesA),
      PublishQ(uri, tag=None, Some(hash(bytesA).hash), bytesB)
    ))
    objectStore.applyChanges(changeSet, clientId)

    val obj = objectStore.getState.get(uri)
    obj should be(defined)
    obj.get should be((bytesB, hash(bytesB), clientId))
  }

  test("should store an object, withdraw it and make sure it's not there anymore") {
    val clientId = ClientId("client1")

    val changeSet = QueryMessage(Seq(PublishQ(uri, tag=None, hash=None, Bytes.fromBase64(Base64("AABBCC==")))))
    objectStore.applyChanges(changeSet, clientId)

    val withdrawSet = QueryMessage(Seq(WithdrawQ(uri, tag=None, hash(Base64("AABBCC==")).hash)))
    objectStore.applyChanges(withdrawSet, clientId)

    val obj = objectStore.getState.get(uri)
    obj should not be defined
  }

  test("should store and withdraw object in the same message") {
    val clientId = ClientId("client1")

    objectStore.applyChanges(
      QueryMessage(Seq(
        PublishQ(uri, tag = None, hash = None, Bytes.fromBase64(Base64("AABBCC=="))),
        WithdrawQ(uri, tag = None, hash(Base64("AABBCC==")).hash)
      )), clientId)

    val obj = objectStore.getState.get(uri)
    obj should not be defined
  }

}
