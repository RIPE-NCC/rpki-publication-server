package net.ripe.rpki.publicationserver.store

import java.net.URI

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId
import slick.dbio.Effect.Write
import slick.driver.DerbyDriver.api._
import slick.profile.FixedSqlAction

import scala.concurrent.{Await, Future}

class ObjectStore extends Hashing {

  lazy val conf = wire[AppConfig]

  import DB._

  val db = DB.db

  private type StoredTuple = (String, String, String, String)

  def list(cliendId: ClientId): Seq[RRDPObject] = {
    val ClientId(cId) = cliendId
    getSeq(objects.filter(_.clientId === cId))
  }

  def listAll(serial: Long): Option[Seq[RRDPObject]] = {

    // TODO Implement it as one transactional action
    val correctSerial = Await.result(db.run(serverStates.filter(_.serialNumber === serial).exists.result), conf.defaultTimeout)
    if (correctSerial)
      Some(getSeq(objects))
    else
      None
  }

  def listAll: Seq[RRDPObject] = getSeq(objects)

  def getAllAction = mapQ(objects)

  def insertAction(clientId: ClientId, obj: RRDPObject) = {
    val (base64, hash, uri) = obj
    objects += (base64.value, hash.hash, uri.toString, clientId.value)
  }

  def updateAction(clientId: ClientId, obj: RRDPObject) = {
    val (base64, hash, uri) = obj
    objects.filter(_.uri === uri.toString).update {
      (base64.value, hash.hash, uri.toString, clientId.value)
    }
  }

  def deleteAction(clientId: ClientId, hash: Hash) =
    objects.filter(_.hash === hash.hash).delete

  def find(uri: URI): Option[RRDPObject] =
    getSeq(objects.filter(_.uri === uri.toString)).headOption

  def atomic[E <: Effect, T](actions: Seq[DBIOAction[_, NoStream, E]], f: => T) : T =
    Await.result(db.run {
      (for {
        _ <- DBIO.seq(actions: _*)
        t <- liftDB(f)
      } yield t).transactionally
    }, conf.defaultTimeout)

  def clear() = Await.result(db.run(objects.delete), conf.defaultTimeout)

  private def mapQ(q: Query[RepoObject, StoredTuple, Seq]) = q.result.map {
    _.map { o =>
      val (b64, h, u, _) = o
      (Base64(b64), Hash(h), new URI(u))
    }
  }

  private def getSeq(q: Query[RepoObject, StoredTuple, Seq]): Seq[RRDPObject] =
    Await.result(db.run(mapQ(q)), conf.defaultTimeout)


  type State = Map[URI, (Base64, Hash)]

  def getState : State = {
    listAll.map { o =>
      val (base64, hash, uri) = o
      uri -> (base64, hash)
    }.toMap
  }

  def applyChanges(changeSet: QueryMessage, clientId: ClientId): Future[Unit] = {
    val actions: Seq[FixedSqlAction[Int, NoStream, Write]] = changeSet.pdus.map {
      case WithdrawQ(uri, tag, hash) =>
        deleteAction(clientId, Hash(hash))
      case PublishQ(uri, tag, None, base64) =>
        insertAction(clientId, (base64, hash(base64), uri))
      case PublishQ(uri, tag, Some(h), base64) =>
        updateAction(clientId, (base64, hash(base64), uri))
    }
    db.run(DBIO.seq(actions: _*).transactionally)
  }

}

object ObjectStore {
  // it's stateless, so we can return new instance every time
  def get = new ObjectStore
}
