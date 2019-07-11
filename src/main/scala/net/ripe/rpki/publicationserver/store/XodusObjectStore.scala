package net.ripe.rpki.publicationserver.store

import java.net.URI

import com.softwaremill.macwire.MacwireMacros._
import jetbrains.exodus.entitystore.{Entity, StoreTransaction, StoreTransactionalComputable, StoreTransactionalExecutable}
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.DB.RRDPObject

import scala.collection.JavaConversions._
import scala.concurrent.Future

class XodusObjectStore extends Hashing {

  lazy val conf: AppConfig = wire[AppConfig]

  import XodusDB._

  private val OBJECT_ENTITY_NAME = "object"

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

  private def insertAction(txn: StoreTransaction, obj: RRDPObject): Unit = {
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

  private def updateAction(txn: StoreTransaction, obj: RRDPObject) = {
    val (base64, hash, uri, clientId) = obj
    txn.find(OBJECT_ENTITY_NAME, "uri", uri.toString).
      foreach(e => fillEntity(base64, hash, uri, clientId, e))
  }

  private def deleteAction(txn: StoreTransaction, hash: Hash) = {
    txn.find(OBJECT_ENTITY_NAME, "hash", hash.hash).
      foreach(e => e.delete())
  }

  def clear() = inTx { txn =>
    txn.getAll(OBJECT_ENTITY_NAME).foreach(e => e.delete())
  }

  def getState: ObjectStore.State = withReadTx { txn =>
    txn.getAll(OBJECT_ENTITY_NAME).map { e =>
      val base64 = Base64(e.getProperty("base64").toString)
      val hash = Hash(e.getProperty("hash").toString)
      val uri = URI.create(e.getProperty("uri").toString)
      val clientId = ClientId(e.getProperty("clientId").toString)
      uri -> (base64, hash, clientId)
    }.toMap
  }

  def applyChanges(changeSet: QueryMessage, clientId: ClientId): Future[Unit] =
    Future.successful {
      inTx { txn =>
        changeSet.pdus.foreach {
          case WithdrawQ(uri, tag, hash) =>
            deleteAction(txn, Hash(hash))
          case PublishQ(uri, tag, None, base64) =>
            insertAction(txn, (base64, hash(base64), uri, clientId))
          case PublishQ(uri, tag, Some(h), base64) =>
            updateAction(txn, (base64, hash(base64), uri, clientId))
        }
      }
    }

  def check() = ()

  // Await.result(db.run(DBIO.seq(objects.take(1).result)), conf.defaultTimeout)
}

object XodusObjectStore {
  type State = Map[URI, (Base64, Hash, ClientId)]
  // it's stateless, so we can return new instance every time
  def get = new XodusObjectStore
}


