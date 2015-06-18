package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver._
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class ObjectStore(db: DB.DBType) extends RepoObjectDB with Hashing {

  import DB._

  override def list(cliendId: ClientId): Seq[RRDPObject] = {
    val ClientId(cId) = cliendId
    getSeq(objects.filter(_.clientId === cId))
  }

  override def listAll: Seq[RRDPObject] = getSeq(objects)

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
    }, Duration.Inf)

  def clear() = Await.result(db.run(objects.delete), Duration.Inf)

  private def getSeq(q: Query[RepoObject, (String, String, String, String), Seq]): Seq[RRDPObject] =
    Await.result(db.run(q.result).map {
      _.map { o =>
        val (b64, h, u, _) = o
        (Base64(b64), Hash(h), new URI(u))
      }
    }, Duration.Inf)

}
