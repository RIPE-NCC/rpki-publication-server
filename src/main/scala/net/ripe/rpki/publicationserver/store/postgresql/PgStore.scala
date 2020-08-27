package net.ripe.rpki.publicationserver.store.postresql

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import scalikejdbc.{ConnectionPool, ConnectionPoolSettings, DB, DBSession, IsolationLevel, NoExtractor, SQL, scalikejdbcSQLInterpolationImplicitDef}

class PgStore(val pgConfig: PgConfig) extends Hashing with Logging {

  type RRDPObject = (Bytes, Hash, URI, ClientId)

  def inTx[T](f : DBSession => T) : T= {
    DB(ConnectionPool.borrow())
      .isolationLevel(IsolationLevel.Serializable)
      .localTx(f)
  }

  def getInsertSql(obj: RRDPObject): SQL[Nothing, NoExtractor] = {
    val (Bytes(bytes), Hash(hashStr), uri, ClientId(clientId)) = obj
    sql"""
      WITH
        existing AS (
          SELECT id FROM objects
          WHERE hash = ${hashStr}
        ),
        inserted AS (
          INSERT INTO objects (hash, content)
          SELECT ${hashStr}, ${bytes}
          WHERE NOT EXISTS (SELECT * FROM existing)
          RETURNING id
        )
        INSERT INTO object_urls
        SELECT ${uri.toString}, z.id, ${clientId}
        FROM (
          SELECT id FROM inserted
          UNION ALL
          SELECT id FROM existing
        ) AS z
    """
  }

  def getUpdateSql(oldHash: String, obj: RRDPObject): SQL[Nothing, NoExtractor] = {
    val (Bytes(bytes), Hash(newHashStr), uri, ClientId(clientId)) = obj
    sql"""
      WITH
        old_ignored AS (
          DELETE FROM objects
          WHERE hash = ${oldHash}
          RETURNING id
        ),
        existing AS (
          SELECT id FROM objects
          WHERE hash = ${newHashStr}
        ),
        new_object AS (
          INSERT INTO objects (hash, content)
          SELECT ${newHashStr}, ${bytes}
          WHERE NOT EXISTS (SELECT * FROM existing)
          RETURNING id
        ),
        new_object_id AS (
          SELECT id FROM new_object
          UNION ALL
          SELECT id FROM existing
        )
        INSERT INTO object_urls
        SELECT ${uri.toString}, z.id, ${clientId}
        FROM new_object_id AS z
        ON CONFLICT (url) DO
            UPDATE SET
              object_id = (SELECT id FROM new_object_id),
              client_id = ${clientId}
    """
  }

  private def getDeleteSql(hash: String) : SQL[Nothing, NoExtractor] = {
    sql"""DELETE FROM objects WHERE hash = ${hash}"""
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
    val toSql: QueryPdu => SQL[Nothing, NoExtractor] = {
      case WithdrawQ(_, _, hash) =>
        getDeleteSql(hash)
      case PublishQ(uri, _, None, bytes) =>
        val h = hash(bytes)
        getInsertSql((bytes, h, uri, clientId))
      case PublishQ(uri, _, Some(oldHash), bytes) =>
        val h = hash(bytes)
        getUpdateSql(oldHash, (bytes, h, uri, clientId))
    }

    inTx { implicit session =>
      changeSet.pdus.foreach { pdu =>
        toSql(pdu).execute().apply()
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


