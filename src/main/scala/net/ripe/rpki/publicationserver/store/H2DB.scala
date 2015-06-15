package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver.{Base64, Hash}
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class H2DB extends DB {

  import DB._

  private val db = Database.forConfig("h2mem1")

  override def list(cliendId: ClientId): Seq[RRDPObject] = {
    val ClientId(cId) = cliendId
    val query = objects.filter(_.clientId === cId)
    val future = db.run(query.result)
    val seqFuture = future.map { _.map { o =>
        val (b64, h, u, _) = o
        (Base64(b64), Hash(h), new URI(u))
      }
    }
    Await.result(seqFuture, Duration.Inf)
  }

  override def publish(cliendId: ClientId, obj: RRDPObject): Unit = {
    val (base64, hash, uri) = obj
    val insertActions = DBIO.seq(
      objects += (uri.toString, base64.value, hash.hash, cliendId.value)
    )
    Await.result(db.run(insertActions), Duration.Inf)
  }

  override def withdraw(cliendId: ClientId, obj: RRDPObject): Unit = {
    val (base64, hash, uri) = obj
    val deleteActions = DBIO.seq(
      objects.filter(_.hash === hash.hash).delete
    )
    Await.result(db.run(deleteActions), Duration.Inf)
  }
}
