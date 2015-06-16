package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver.{Base64, Hash}
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class ObjectStore extends DB {

  import DB._

  val db = Database.forConfig("h2mem1")

  override def list(cliendId: ClientId): Seq[RRDPObject] = {
    val ClientId(cId) = cliendId
    getSeq(objects.filter(_.clientId === cId))
  }

  override def publish(clientId: ClientId, obj: RRDPObject): Unit = {
    val (base64, hash, uri) = obj
    val insertActions = DBIO.seq(
      objects +=(base64.value, hash.hash, uri.toString, clientId.value)
    )
    Await.result(db.run(insertActions), Duration.Inf)
  }

  override def withdraw(clientId: ClientId, hash: Hash): Unit = {
    val deleteActions = DBIO.seq(
      objects.filter(_.hash === hash.hash).delete
    )
    Await.result(db.run(deleteActions), Duration.Inf)
  }

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

  def find(uri: URI): Option[RRDPObject] = {
    getSeq(objects.filter(_.uri === uri.toString)).headOption
  }

  private def getSeq(q: Query[RepoObject, (String, String, String, String), Seq]) = {
    val seqFuture = db.run(q.result).map { _.map { o =>
        val (b64, h, u, _) = o
        (Base64(b64), Hash(h), new URI(u))
      }
    }
    Await.result(seqFuture, Duration.Inf)
  }

  def atomic[E <: Effect](actions: Seq[DBIOAction[_, NoStream, E]]) =
    Await.result(db.run(DBIO.seq(actions: _*).transactionally), Duration.Inf)

  def clear() = Await.result(db.run(objects.delete), Duration.Inf)

}
