package net.ripe.rpki.publicationserver.store.postresql

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import scalikejdbc.{ConnectionPool, ConnectionPoolSettings, DB, DBSession, IsolationLevel, NoExtractor, SQL, scalikejdbcSQLInterpolationImplicitDef}

class PgStore(val pgConfig: PgConfig) extends Hashing with Logging {

  type RRDPObject = (Bytes, Hash, URI, ClientId)

  case class DbVersion(value: Long)

  def inTx[T](f : DBSession => T) : T= {
    DB(ConnectionPool.borrow())
      .isolationLevel(IsolationLevel.Serializable)
      .localTx(f)
  }

  def getInsertSql(obj: RRDPObject, dbVersion: DbVersion): SQL[Nothing, NoExtractor] = {
    val (Bytes(bytes), _, uri, ClientId(clientId)) = obj
    sql"SELECT create_object(${dbVersion.value}, ${bytes},  ${uri.toString}, ${clientId})"
  }

  def getUpdateSql(oldHash: String, obj: RRDPObject, dbVersion: DbVersion): SQL[Nothing, NoExtractor] = {
    val (Bytes(bytes), _, uri, ClientId(clientId)) = obj
    sql"SELECT replace_object(${dbVersion.value}, ${bytes}, ${oldHash}, ${uri.toString}, ${clientId})"
  }

  private def getDeleteSql(hash: String, clientId: ClientId, dbVersion: DbVersion) : SQL[Nothing, NoExtractor] = {
    sql"SELECT delete_object(${dbVersion.value}, ${hash}, ${clientId.value})"
  }

  def clear(): Unit = DB.localTx { implicit session =>
    sql"DELETE FROM objects".update.apply()
  }

  def getState: ObjectStore.State = DB.localTx { implicit session =>
    sql"""SELECT hash, url, client_id, content
         FROM objects o
         INNER JOIN object_urls ou ON ou.object_id = o.id"""
      .map { rs =>
        val hash = Hash(rs.string(1))
        val uri = URI.create(rs.string(2))
        val clientId = ClientId(rs.string(3))
        val bytes = Bytes.fromStream(rs.binaryStream(4))
        uri -> (bytes, hash, clientId)
      }
      .list
      .apply
      .toMap
  }

  def applyChanges(changeSet: QueryMessage, clientId: ClientId): Unit = {
    def toSql(pdu: QueryPdu, version: DbVersion) = {
      pdu match {
        case WithdrawQ(_, _, hash) =>
          getDeleteSql(hash, clientId, version)
        case PublishQ(uri, _, None, bytes) =>
          val h = hash(bytes)
          getInsertSql((bytes, h, uri, clientId), version)
        case PublishQ(uri, _, Some(oldHash), bytes) =>
          val h = hash(bytes)
          getUpdateSql(oldHash, (bytes, h, uri, clientId), version)
      }
    }

    inTx { implicit session =>
      val version = sql"SELECT last_pending_version()"
        .map(rs => rs.long(1))
        .single()
        .apply()
        .map(DbVersion)
        .get
      changeSet.pdus.foreach { pdu =>
        toSql(pdu, version).execute().apply()
      }
    }
  }

  def check() = {
    val z = inTx { implicit session =>
      sql"SELECT 1".map(_.int(1)).single().apply()
    }
    z match {
      case None =>
        throw new Exception("Something is really wrong with the database")
      case Some(i) if i != 1 =>
        throw new Exception(s"Something is really wrong with the database, returned $i instead of 1")
      case _ => ()
    }
  }
}

object PgStore {
  type State = Map[URI, (Bytes, Hash, ClientId)]

  var pgStore : PgStore = _

  def get(pgConfig: PgConfig): PgStore = synchronized {
    if (pgStore == null) {
      val settings = ConnectionPoolSettings(
        initialSize = 5,
        maxSize = 20,
        connectionTimeoutMillis = 3000L,
        validationQuery = "select 1")

      ConnectionPool.singleton(
        pgConfig.url, pgConfig.user, pgConfig.password, settings)

      pgStore = new PgStore(pgConfig)
    }
    pgStore
  }

}


