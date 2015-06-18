package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver.store.DB.{ServerState, RRDPObject}
import net.ripe.rpki.publicationserver.{Base64, Hash}
import slick.jdbc.meta.MTable

import scala.concurrent.Await
import scala.concurrent.duration._

case class ClientId(value: String)

trait RepoObjectDB {
  def list(clientId: ClientId): Seq[RRDPObject]
  def listAll: Seq[RRDPObject]

  def publish(clientId: ClientId, obj: RRDPObject): Unit

  def withdraw(clientId: ClientId, hash: Hash): Unit

  def clear(): Unit
}

trait ServerStateDB {
  def get: ServerState

  def update(serverState: ServerState): Unit
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

  case class ServerState(sessionId: String, serialNumber: Long)

  class ServerStates(tag: Tag) extends Table[ServerState](tag, "META_DATA") {
    def sessionId = column[String]("SESSION_ID", O.PrimaryKey)
    def serialNumber = column[Long]("SERIAL_NUMBER")

    def * = (sessionId, serialNumber) <> (ServerState.tupled, ServerState.unapply)
  }

  val objects = TableQuery[RepoObject]

  val serverStates = TableQuery[ServerStates]

  def tableExists(db: Database, name: String) = {
    Await.result(db.run(MTable.getTables), 1.seconds).exists(_.name.name == name)
  }
}
