package net.ripe.rpki.publicationserver.store.postgresql

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model._
import org.flywaydb.core.Flyway
import org.json4s._
import org.json4s.native.JsonMethods._
import scalikejdbc._

import java.net.URI


case class RollbackException(val error: BaseError) extends Exception

class PgStore(val pgConfig: PgConfig) extends Hashing with Logging {

  def inRepeatableReadTx[T](f: DBSession => T): T = {
    DB(ConnectionPool.borrow())
      .isolationLevel(IsolationLevel.RepeatableRead)
      .localTx(f)
  }

  def clear(): Unit = DB.localTx { implicit session =>
    sql"DELETE FROM object_log".update().apply()
    sql"DELETE FROM objects".update().apply()
    sql"DELETE FROM versions".update().apply()
  }

  def getState = DB.localTx { implicit session =>
    sql"SELECT hash, url, client_id, content FROM current_state ORDER BY url"
      .map { rs =>
        val hash = Hash(rs.bytes(1))
        val url = URI.create(rs.string(2))
        val clientId = ClientId(rs.string(3))
        val content = Bytes.fromStream(rs.binaryStream(4))
        url -> (content, hash, clientId)
      }
      .list()
      .apply()
      .toMap
  }

  def getLog = DB.localTx { implicit session =>
    sql"""SELECT url, old_hash, content
         FROM current_log
         ORDER BY url ASC"""
      .map { rs =>
        val uri = URI.create(rs.string(1))
        val hash = rs.bytesOpt(2).map(bs => Hash(bs))
        val bytes = rs.binaryStreamOpt(3).map(Bytes.fromStream)
        (uri, hash, bytes)
      }
      .list()
      .apply()
  }

  def readState(f: (URI, Hash, Bytes) => Unit)(implicit session: DBSession) = {
    session.fetchSize(200)
    sql"""SELECT url, hash, content
          FROM current_state
          ORDER BY url ASC"""
      .foreach { rs =>
        val uri = URI.create(rs.string(1))
        val hash = Hash(rs.bytes(2))
        val bytes = Bytes.fromStream(rs.binaryStream(3))
        f(uri, hash, bytes)
      }
  }

  def readDelta(sessionId: String, serial: Long)(f: (URI, Option[Hash], Option[Bytes]) => Unit)(implicit session: DBSession) = {
    session.fetchSize(200)
    sql"""SELECT url, old_hash, content
         FROM read_delta($sessionId, $serial)
         ORDER BY url"""
      .foreach { rs =>
        val uri = URI.create(rs.string(1))
        val oldHash = rs.bytesOpt(2).map(bs => Hash(bs))
        val bytes = rs.binaryStreamOpt(3).map(Bytes.fromStream)
        f(uri, oldHash, bytes)
      }
  }

  case class SnapshotInfo(name: String, hash: Hash, size: Long)
  case class DeltaInfo(name: String, hash: Hash, size: Long)

  def getCurrentSessionInfo(implicit session: DBSession) = {

    def toSnapshotInfo(name: Option[String], hash: Option[Array[Byte]], size: Option[Long]) =
      for (n <- name; h <- hash; s <- size)
        yield SnapshotInfo(n, Hash(h), s)

    def toDeltaInfo(name: Option[String], hash: Option[Array[Byte]], size: Option[Long]) =
      for (n <- name; h <- hash; s <- size)
        yield DeltaInfo(n, Hash(h), s)

    sql"""SELECT session_id, serial,
            snapshot_file_name, snapshot_hash, snapshot_size,
            delta_file_name, delta_hash, delta_size
         FROM latest_version"""
      .map(rs => (
        rs.string(1),
        rs.long(2),
        toSnapshotInfo(rs.stringOpt(3), rs.bytesOpt(4), rs.longOpt(5)),
        toDeltaInfo(rs.stringOpt(6), rs.bytesOpt(7), rs.longOpt(8))
      ))
      .single()
      .apply()
  }

  def getReasonableDeltas(sessionId: String)(implicit session: DBSession) = {
    sql"""SELECT serial, delta_hash, delta_file_name
          FROM reasonable_deltas
          WHERE session_id = $sessionId
          ORDER BY serial DESC"""
      .map { rs =>
        (rs.long(1),
          Hash(rs.bytes(2)),
          rs.string(3))
      }
      .list()
      .apply()
  }

  def updateSnapshotInfo(sessionId: String, serial: Long, snapshotFileName: String, hash: Hash, size: Long)(implicit session: DBSession): Unit = {
    sql"""UPDATE versions SET
            snapshot_hash = ${hash.toBytes},
            snapshot_size = $size,
            snapshot_file_name = $snapshotFileName
          WHERE session_id = $sessionId AND serial = $serial"""
      .execute()
      .apply()
  }

  def updateDeltaInfo(sessionId: String, serial: Long, deltaFileName: String, hash: Hash, size: Long)(implicit session: DBSession): Unit = {
    sql"""UPDATE versions SET
            delta_hash = ${hash.toBytes},
            delta_size = $size,
            delta_file_name = $deltaFileName
          WHERE session_id = $sessionId AND serial = $serial"""
      .execute()
      .apply()
  }

  implicit val formats = org.json4s.DefaultFormats

  def applyChanges(changeSet: QueryMessage, clientId: ClientId)(implicit metrics: Metrics): Unit = {

    def executeSql(sql: SQL[Nothing, NoExtractor], onSuccess: => Unit, onFailure: => Unit)(implicit session: DBSession): Unit = {
      val r = sql.map(_.string(1)).single().apply()
      r match {
        case None =>
          onSuccess
        case Some(json) =>
          onFailure
          val error = parse(json).extract[BaseError]
          throw RollbackException(error)
      }
    }

    if (changeSet.pdus.isEmpty) {
      return
    }

    inRepeatableReadTx { implicit session =>
      // Apply all modification while holding a lock on the client ID
      // (which most often is the CA owning the objects)
      sql"SELECT acquire_client_id_lock(${clientId.value})".execute().apply()

      changeSet.pdus.foreach {
        case PublishQ(uri, _, None, _) =>
          executeSql(
            sql"SELECT verify_object_is_absent(${uri.toString})",
            {},
            metrics.failedToAdd())
        case PublishQ(uri, _, Some(oldHash), _) =>
          executeSql(
            sql"SELECT verify_object_is_present(${uri.toString}, ${oldHash.toBytes}, ${clientId.value})",
            {},
            metrics.failedToReplace())
        case WithdrawQ(uri, _, hash) =>
          executeSql(
            sql"SELECT verify_object_is_present(${uri.toString}, ${hash.toBytes}, ${clientId.value})",
            {},
            metrics.failedToDelete())
      }

      changeSet.pdus.foreach {
        case PublishQ(uri, _, None, Bytes(bytes)) =>
          executeSql(
            sql"SELECT create_object(${bytes}, ${uri.toString}, ${clientId.value})",
            metrics.publishedObject(),
            metrics.failedToAdd())
        case PublishQ(uri, _, Some(oldHash), Bytes(bytes)) =>
          executeSql(
            sql"SELECT replace_object(${bytes}, ${oldHash.toBytes}, ${uri.toString}, ${clientId.value})",
            {
              metrics.withdrawnObject()
              metrics.publishedObject()
            },
            metrics.failedToReplace())
        case WithdrawQ(uri, _, hash) =>
          executeSql(
            sql"SELECT delete_object(${uri.toString}, ${hash.toBytes}, ${clientId.value})",
            metrics.withdrawnObject(),
            metrics.failedToDelete())
      }
    }
  }

  def lockVersions(implicit session: DBSession) = {
    sql"SELECT lock_versions()".execute().apply()
  }


  def freezeVersion(implicit session: DBSession) = {
    sql"SELECT session_id, serial, created_at FROM freeze_version()"
      .map(rs => (rs.string(1), rs.long(2), rs.timestamp(3)))
      .single()
      .apply()
      .get
  }

  // Delete old version (for a certain definition of "old") and return their
  // session_id and serial.
  def deleteOldVersions(implicit session: DBSession) = {
    sql"SELECT session_id, serial FROM delete_old_versions()"
      .map(rs => (rs.string(1), rs.long(2)))
      .list()
      .apply()
  }

  def changesExist()(implicit session: DBSession) = {
    sql"SELECT changes_exist()"
      .map(rs => rs.boolean(1))
      .single()
      .apply()
      .get
  }

  def check() = {
    val z = inRepeatableReadTx { implicit session =>
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

  def list(clientId: ClientId) = inRepeatableReadTx { implicit session =>
    sql"""SELECT url, hash FROM current_state
           WHERE client_id = ${clientId.value}"""
      .map(rs => (rs.string(1), Hash(rs.bytes(2))))
      .list()
      .apply()
  }
}

object PgStore extends Logging {
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

  def migrateDB(pgConfig: PgConfig) = {
    logger.info("Migrating the database")
    val flyway = Flyway.configure.dataSource(pgConfig.url, pgConfig.user, pgConfig.password).load
    flyway.migrate()
  }

}
