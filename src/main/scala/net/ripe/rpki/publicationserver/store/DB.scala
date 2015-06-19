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

  def clear(): Unit
}

trait ServerStateDB {
  def get: ServerState

  def update(serverState: ServerState): Unit
}

object DBConfig {
  var useMemoryDatabase = false
}

object DB {

  import slick.driver.H2Driver.api._

  type RRDPObject = (Base64, Hash, URI)

  type DBType = Database

  def db = if (DBConfig.useMemoryDatabase) Database.forConfig("h2mem1") else Database.forConfig("h2fs")

  class RepoObject(tag: Tag) extends Table[(String, String, String, String)](tag, "REPO_OBJECTS") {
    def uri = column[String]("URI", O.PrimaryKey)
    def hash = column[String]("HASH")
    def base64 = column[String]("BASE64")
    def clientId = column[String]("CLIENT_ID")

    def * = (base64, hash, uri, clientId)
  }

  case class ServerState(sessionId: String, serialNumber: Long) {
    def next = ServerState(sessionId, serialNumber + 1)
  }

  class ServerStates(tag: Tag) extends Table[ServerState](tag, "SERVER_STATES") {
    def sessionId = column[String]("SESSION_ID", O.PrimaryKey)
    def serialNumber = column[Long]("SERIAL_NUMBER")

    def * = (sessionId, serialNumber) <> (ServerState.tupled, ServerState.unapply)
  }

  class DeltaPdu(tag: Tag) extends Table[(String, String, String, String, Long, Char)](tag, "DELTAS") {
    def uri = column[String]("URI")
    def hash = column[String]("HASH")
    def base64 = column[String]("BASE64")
    def clientId = column[String]("CLIENT_ID")
    def serial = column[Long]("SERIAL")
    def changeType = column[Char]("CHANGE_TYPE")

    def pk = primaryKey("pk_a", (uri, serial, changeType))
    def * = (base64, hash, uri, clientId, serial, changeType)
  }


  val objects = TableQuery[RepoObject]

  val serverStates = TableQuery[ServerStates]

  val deltas = TableQuery[DeltaPdu]

  def tableExists(db: Database, name: String) = {
    Await.result(db.run(MTable.getTables), 1.seconds).exists(_.name.name == name)
  }

  def liftDB[T](f: => T) = try {
    val x = f // force value calculation
    DBIO.successful(x)
  }
  catch {
    case e: Exception => DBIO.failed(e)
  }


}
