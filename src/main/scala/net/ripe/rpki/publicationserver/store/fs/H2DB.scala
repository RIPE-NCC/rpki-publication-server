package net.ripe.rpki.publicationserver.store.fs

import java.net.URI

import net.ripe.rpki.publicationserver.{Hash, Base64}
import slick.driver.H2Driver
import slick.driver.H2Driver.api._

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

class H2DB extends DB {

  class RepoObject(tag: Tag) extends Table[(String, String, String, String)](tag, "RepoObjects") {
    def uri = column[String]("URI", O.PrimaryKey)

    def hash = column[String]("HASH")

    def base64 = column[String]("BASE64")

    def clientId = column[String]("CLIENT_ID")

    def * = (uri, base64, hash, clientId)
  }

  private val objects = TableQuery[RepoObject]

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
}
