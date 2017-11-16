package net.ripe.rpki.publicationserver.store

import java.net.URI

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId
import slick.jdbc.DerbyProfile.api._
import slick.lifted.Query

import scala.concurrent.{Await, Future}

class ObjectStore extends Hashing {

  lazy val conf: AppConfig = wire[AppConfig]

  import DB._

  val db = DB.db

  private type StoredTuple = (String, String, String, String)

  private def listAll: Seq[RRDPObject] = getSeq(objects)

  private def insertAction(obj: RRDPObject) = {
    val (base64, hash, uri, clientId) = obj
    objects += (base64.value, hash.hash, uri.toString, clientId.value)
  }

  private def updateAction(obj: RRDPObject) = {
    val (base64, hash, uri, clientId) = obj
    objects.filter(_.uri === uri.toString).update {
      (base64.value, hash.hash, uri.toString, clientId.value)
    }
  }

  private def deleteAction(clientId: ClientId, hash: Hash) =
    objects.filter(_.hash === hash.hash).delete

  def clear() = Await.result(db.run(objects.delete), conf.defaultTimeout)

  private def mapQ(q: Query[RepoObject, StoredTuple, Seq]) = q.result.map {
    _.map { o =>
      val (b64, h, u, clientId) = o
      (Base64(b64), Hash(h), new URI(u), ClientId(clientId))
    }
  }

  private def getSeq(q: Query[RepoObject, StoredTuple, Seq]): Seq[RRDPObject] =
    Await.result(db.run(mapQ(q)), conf.defaultTimeout)


  def getState : ObjectStore.State = {
    listAll.map { o =>
      val (base64, hash, uri, clientId) = o
      uri -> (base64, hash, clientId)
    }.toMap
  }

  def applyChanges(changeSet: QueryMessage, clientId: ClientId): Future[Unit] = {
    val actions = changeSet.pdus.map {
      case WithdrawQ(uri, tag, hash) =>
        deleteAction(clientId, Hash(hash))
      case PublishQ(uri, tag, None, base64) =>
        insertAction((base64, hash(base64), uri, clientId))
      case PublishQ(uri, tag, Some(h), base64) =>
        updateAction((base64, hash(base64), uri, clientId))
    }
    db.run(DBIO.seq(actions: _*).transactionally)
  }

  def check() = Await.result(db.run(DBIO.seq(objects.take(1).result)), conf.defaultTimeout)
}

object ObjectStore {
  type State = Map[URI, (Base64, Hash, ClientId)]
  // it's stateless, so we can return new instance every time
  def get = new ObjectStore
}
