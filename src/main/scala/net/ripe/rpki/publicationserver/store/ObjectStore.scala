package net.ripe.rpki.publicationserver.store

import java.io.ByteArrayInputStream
import java.net.URI

import com.softwaremill.macwire.MacwireMacros._
import jetbrains.exodus.entitystore.{Entity, StoreTransaction, StoreTransactionalComputable, StoreTransactionalExecutable}
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId

import scala.collection.JavaConversions._

class ObjectStore extends Hashing with Logging {

  lazy val conf: AppConfig = wire[AppConfig]

  import XodusDB._

  private val OBJECT_ENTITY_NAME = "object"

  private val BYTES_FIELD_NAME = "bytes"
  private val BASE64_FIELD_NAME = "base64"
  private val HASH_FIELD_NAME = "hash"
  private val URI_FIELD_NAME = "uri"
  private val CLIENT_ID_FIELD_NAME = "clientId"

  type RRDPObject = (Bytes, Hash, URI, ClientId)

  private def inTx(f: StoreTransaction => Unit): Unit = {
    entityStore.executeInTransaction(new StoreTransactionalExecutable() {
      override def execute(txn: StoreTransaction): Unit = f(txn)
    })
  }

  private def withReadTx[T](f: StoreTransaction => T): T = {
    entityStore.computeInReadonlyTransaction(new StoreTransactionalComputable[T]() {
      override def compute(txn: StoreTransaction): T = f(txn)
    })
  }

  private type StoredTuple = (String, String, String, String)

  private def insert(txn: StoreTransaction, obj: RRDPObject): Unit = {
    val (bytes, hash, uri, clientId) = obj
    val e = txn.newEntity("object")
    fillEntity(bytes, hash, uri, clientId, e)
  }

  private def fillEntity(bytes: Bytes, hash: Hash, uri: URI, clientId: ClientId, e: Entity) = {
    e.setBlob(BYTES_FIELD_NAME, new ByteArrayInputStream(bytes.value))
    e.setProperty(HASH_FIELD_NAME, hash.hash)
    e.setProperty(URI_FIELD_NAME, uri.toString)
    e.setProperty(CLIENT_ID_FIELD_NAME, clientId.value)
  }

  private def update(txn: StoreTransaction, obj: RRDPObject): Unit = {
    val (bytes, hash, uri, clientId) = obj
    txn.find(OBJECT_ENTITY_NAME, "uri", uri.toString).
      foreach(e => fillEntity(bytes, hash, uri, clientId, e))
  }

  private def delete(txn: StoreTransaction, hash: Hash): Unit = {
    txn.find(OBJECT_ENTITY_NAME, "hash", hash.hash).
      foreach(e => e.delete())
  }

  def clear(): Unit = inTx { txn =>
    txn.getAll(OBJECT_ENTITY_NAME).foreach(e => e.delete())
  }

  private def getBytes(e: Entity, uri: URI) = {
    // This is to preserve back-compatibility with the base64-storing version.
    // We do not store base64 (see fillEntity method), but for older objects
    // it can be set, so we can use it instead of bytes.
    val bytes = e.getBlob(BYTES_FIELD_NAME)
    if (bytes != null) {
      Bytes.fromStream(bytes)
    } else {
      val base64 = e.getProperty(BASE64_FIELD_NAME)
      if (base64 != null) {
        Bytes.fromBase64(Base64(base64.toString))
      } else {
        // This is just to prevent an NPE and it doesn't make a lot of sense
        logger.error(s"Object ${uri} has neither 'bytes' nor 'base64' field.")
        Bytes(Array())
      }
    }
  }

  def getState: ObjectStore.State = withReadTx { txn =>
    txn.getAll(OBJECT_ENTITY_NAME).map { e =>
      val hash = Hash(e.getProperty(HASH_FIELD_NAME).toString)
      val uri = URI.create(e.getProperty(URI_FIELD_NAME).toString)
      val clientId = ClientId(e.getProperty(CLIENT_ID_FIELD_NAME).toString)
      uri -> (getBytes(e, uri), hash, clientId)
    }.toMap
  }

  def applyChanges(changeSet: QueryMessage, clientId: ClientId): Unit =
    inTx { txn =>
      changeSet.pdus.foreach {
        case WithdrawQ(uri, _, hash) =>
          delete(txn, Hash(hash))
        case PublishQ(uri, _, None, bytes) =>
          val h = hash(bytes)
          insert(txn, (bytes, h, uri, clientId))
        case PublishQ(uri, _, Some(oldHash), bytes) =>
          val h = hash(bytes)
          update(txn, (bytes, h, uri, clientId))
          logger.debug(s"Replacing $uri with hash $oldHash -> $h")
      }
    }

  def check() = ()
}

object ObjectStore {
  type State = Map[URI, (Bytes, Hash, ClientId)]

  // it's stateless, so we can return new instance every time
  def get = new ObjectStore
}


