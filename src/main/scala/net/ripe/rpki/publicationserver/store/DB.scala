package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver.store.DB.RRDPObject
import net.ripe.rpki.publicationserver.{Base64, Hash}
import slick.jdbc.meta.MTable

import scala.concurrent.Await
import scala.concurrent.duration._

case class ClientId(value: String)

trait DB {
  def list(cliendId: ClientId): Seq[RRDPObject]
  def listAll: Seq[RRDPObject]

  def publish(cliendId: ClientId, obj: RRDPObject): Unit

  def withdraw(cliendId: ClientId, hash: Hash): Unit

  def clear(): Unit
}

object DB {

  import slick.driver.H2Driver.api._

  type RRDPObject = (Base64, Hash, URI)

  class RepoObject(tag: Tag) extends Table[(String, String, String, String)](tag, "REPO_OBJECTS") {
    def uri = column[String]("URI", O.PrimaryKey)
    def hash = column[String]("HASH")
    def base64 = column[String]("BASE64")
    def clientId = column[String]("CLIENT_ID")

    def * = (base64, hash, uri, clientId)
  }

  val objects = TableQuery[RepoObject]

  def tableExists(db: Database, name: String) = {
    Await.result(db.run(MTable.getTables), 1.seconds).exists(_.name.name == name)
  }
}
