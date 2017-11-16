package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.concurrent.Executors

import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.{Base64, Hash}
import slick.jdbc.meta.MTable

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object DBConfig {
  var useMemoryDatabase = false
}

object DB {

  import slick.jdbc.DerbyProfile.api._

  // define dedicated unbounded EC for slick,
  // to prevent it being stuck when the default EC is exhausted
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  type RRDPObject = (Base64, Hash, URI, ClientId)

  type DBType = Database

  lazy val db = if (DBConfig.useMemoryDatabase) Database.forConfig("derbymem") else Database.forConfig("derbyfs")

  class RepoObject(tag: Tag) extends Table[(String, String, String, String)](tag, "REPO_OBJECTS") {
    def uri = column[String]("URI", O.PrimaryKey)
    def hash = column[String]("HASH")
    def base64 = column[String]("BASE64", O.SqlType("CLOB"))
    def clientId = column[String]("CLIENT_ID")

    def * = (base64, hash, uri, clientId)
    def idxClientId = index("IDX_CLID", clientId)
  }

  class Attributes(tag: Tag) extends Table[(String, String)](tag, "ATTRIBUTES") {
    def name = column[String]("NAME", O.PrimaryKey)
    def value = column[String]("VALUE")
    def * = (name, value)
  }

  class DeltaPdu(tag: Tag) extends Table[(String, Option[String], Option[String], String, Long, Char)](tag, "DELTAS") {
    def uri = column[String]("URI")
    def hash = column[Option[String]]("HASH")
    def base64 = column[Option[String]]("BASE64", O.SqlType("CLOB"))
    def clientId = column[String]("CLIENT_ID")
    def serial = column[Long]("SERIAL")
    def changeType = column[Char]("CHANGE_TYPE")

    def pk = primaryKey("pk_a", (uri, serial, changeType))
    def * = (uri, hash, base64, clientId, serial, changeType)
  }

  val objects = TableQuery[RepoObject]

  val deltas = TableQuery[DeltaPdu]

  val attributes = TableQuery[Attributes]

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
