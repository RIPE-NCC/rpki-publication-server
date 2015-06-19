package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.model.ServerState
import net.ripe.rpki.publicationserver.{Base64, Hash}
import slick.jdbc.meta.MTable

import scala.concurrent.Await
import scala.concurrent.duration._

object DBConfig {
  var useMemoryDatabase = false
}

object DB {

  import slick.driver.H2Driver.api._

  type RRDPObject = (Base64, Hash, URI)

  type DBType = Database

  def db = if (DBConfig.useMemoryDatabase) Database.forConfig("h2mem") else Database.forConfig("h2fs")

  class RepoObject(tag: Tag) extends Table[(String, String, String, String)](tag, "REPO_OBJECTS") {
    def uri = column[String]("URI", O.PrimaryKey)
    def hash = column[String]("HASH")
    def base64 = column[String]("BASE64")
    def clientId = column[String]("CLIENT_ID")

    def * = (base64, hash, uri, clientId)
  }

  class ServerStates(tag: Tag) extends Table[ServerState](tag, "SERVER_STATES") {
    def sessionId = column[String]("SESSION_ID", O.PrimaryKey)
    def serialNumber = column[Long]("SERIAL_NUMBER")

    def * = (sessionId, serialNumber) <> (mapRow, unMapRow )

    private def mapRow(tuple: (String, Long)) = ServerState(UUID.fromString(tuple._1), tuple._2)

    private def unMapRow(s: ServerState) = Some((s.sessionId.toString, s.serialNumber))
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
