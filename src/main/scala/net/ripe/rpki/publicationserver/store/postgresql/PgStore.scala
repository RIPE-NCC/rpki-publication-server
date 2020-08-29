package net.ripe.rpki.publicationserver.store.postresql

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model.{ClientId, RRDPObject}
import net.ripe.rpki.publicationserver.store.ObjectStore
import org.json4s._
import org.json4s.native.JsonMethods._
import scalikejdbc.{ConnectionPool, ConnectionPoolSettings, DB, DBSession, IsolationLevel, NoExtractor, SQL, scalikejdbcSQLInterpolationImplicitDef}


class RollbackException(val error: BaseError) extends Exception

class PgStore(val pgConfig: PgConfig) extends Hashing with Logging {

  def inTx[T](f: DBSession => T): T = {
    DB(ConnectionPool.borrow())
      .isolationLevel(IsolationLevel.RepeatableRead)
      .localTx(f)
  }

  def getInsertSql(obj: RRDPObject) = {
    val (Bytes(bytes), _, uri, ClientId(clientId)) = obj
    sql"SELECT create_object(${bytes},  ${uri.toString}, ${clientId})"
  }

  def getUpdateSql(oldHash: String, obj: RRDPObject) = {
    val (Bytes(bytes), _, uri, ClientId(clientId)) = obj
    sql"SELECT replace_object(${bytes}, ${oldHash}, ${uri.toString}, ${clientId})"
  }

  private def getDeleteSql(uri: URI, hash: String, clientId: ClientId) = {
    sql"SELECT delete_object(${uri.toString}, ${hash}, ${clientId.value})"
  }

  def clear(): Unit = DB.localTx { implicit session =>
    sql"DELETE FROM objects".update.apply()
  }

  def getState: ObjectStore.State = DB.localTx { implicit session =>
    sql"SELECT * FROM current_state"
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

  implicit val formats = org.json4s.DefaultFormats

  def applyChanges(changeSet: QueryMessage, clientId: ClientId, metrics: Metrics): Unit = {

    def executeSql(sql: SQL[Nothing, NoExtractor], onSuccess: => Unit, onFailure: => Unit)(implicit session: DBSession): Unit = {
      val r = sql.map(_.string(1)).single().apply()
      r match {
        case None =>
          onSuccess
        case Some(json) =>
          onFailure
          val error = parse(json).extract[BaseError]
          throw new RollbackException(error)
      }
    }

    inTx { implicit session =>
      sql"SELECT acquire_client_id_lock(${clientId.value})".execute().apply()

      changeSet.pdus.foreach {
        case WithdrawQ(uri, _, hash) =>
          executeSql(
            getDeleteSql(uri, hash, clientId),
            metrics.withdrawnObject(),
            metrics.failedToDelete())
        case PublishQ(uri, _, None, bytes) =>
          val h = hash(bytes)
          executeSql(
            getInsertSql((bytes, h, uri, clientId)),
            metrics.publishedObject(),
            metrics.failedToAdd())
        case PublishQ(uri, _, Some(oldHash), bytes) =>
          val h = hash(bytes)
          executeSql(getUpdateSql(oldHash, (bytes, h, uri, clientId)),
            {
              metrics.withdrawnObject();
              metrics.publishedObject()
            },
            metrics.failedToReplace())
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

  def list(clientId: ClientId) = inTx { implicit session =>
    sql"""SELECT url, hash
           FROM current_state
           WHERE client_id = ${clientId.value}"""
      .map(rs => (rs.string(1), rs.string(2)))
      .list
      .apply
  }
}

object PgStore {
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



