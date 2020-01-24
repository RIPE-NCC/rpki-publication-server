package net.ripe.rpki.publicationserver.store

import java.net.URI

import com.softwaremill.macwire.MacwireMacros._
import jetbrains.exodus.entitystore.{Entity, EntityIterable, StoreTransaction, StoreTransactionalComputable, StoreTransactionalExecutable}
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId

import scala.collection.JavaConversions._

class ObjectStore extends Hashing with Logging {

  lazy val conf: AppConfig = wire[AppConfig]

  import XodusDB._

  private val OBJECT_ENTITY_NAME = "object"

  type RRDPObject = (Base64, Hash, URI, ClientId)

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
    val (base64, hash, uri, clientId) = obj
    val e = txn.newEntity("object")
    fillEntity(base64, hash, uri, clientId, e)
  }

  private def fillEntity(base64: Base64, hash: Hash, uri: URI, clientId: ClientId, e: Entity) = {
    e.setProperty("base64", base64.value)
    e.setProperty("hash", hash.hash)
    e.setProperty("uri", uri.toString)
    e.setProperty("clientId", clientId.value)
  }

  private def update(txn: StoreTransaction, obj: RRDPObject): Unit = {
    val (base64, hash, uri, clientId) = obj
    txn.find(OBJECT_ENTITY_NAME, "uri", uri.toString).
      foreach(e => fillEntity(base64, hash, uri, clientId, e))
  }

  private def delete(txn: StoreTransaction, hash: Hash): Unit = {
    txn.find(OBJECT_ENTITY_NAME, "hash", hash.hash).
      foreach(e => e.delete())
  }

  def clear(): Unit = inTx { txn =>
    txn.getAll(OBJECT_ENTITY_NAME).foreach(e => e.delete())
  }

  def getState: ObjectStore.State = withReadTx { txn =>
    val iterable: EntityIterable = txn.getAll(OBJECT_ENTITY_NAME)
    iterable.map { e =>
      val base64 = Base64(e.getProperty("base64").toString)
      val hash = Hash(e.getProperty("hash").toString)
      val uri = URI.create(e.getProperty("uri").toString)
      val clientId = ClientId(e.getProperty("clientId").toString)
      uri -> (base64, hash, clientId)
    }.toMap
  }

  def applyChanges(changeSet: QueryMessage, clientId: ClientId): Unit =
      inTx { txn =>
        changeSet.pdus.foreach {
          case WithdrawQ(uri, _, hash) =>
            delete(txn, Hash(hash))
          case PublishQ(uri, _, None, base64) =>
            val h = hash(base64)
            insert(txn, (base64, h, uri, clientId))
          case PublishQ(uri, _, Some(oldHash), base64) =>
            val h = hash(base64)
            update(txn, (base64, h, uri, clientId))
            logger.debug(s"Replacing $uri with hash $oldHash -> $h")
        }
      }

  // TODO Implement
  def check(): Unit = ()
}

object ObjectStore {
  type State = Map[URI, (Base64, Hash, ClientId)]

  // it's stateless, so we can return new instance every time
  def get = new ObjectStore
}


