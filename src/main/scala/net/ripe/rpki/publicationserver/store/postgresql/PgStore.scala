package net.ripe.rpki.publicationserver.store.postresql

import java.net.URI

import com.softwaremill.macwire._
import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import scalikejdbc.{DB, DBSession, NoExtractor, SQL, scalikejdbcSQLInterpolationImplicitDef}

class PgStore extends Hashing with Logging {

  lazy val conf: AppConfig = wire[AppConfig]

  type RRDPObject = (Bytes, Hash, URI, ClientId)

  def getInsertSql(obj: RRDPObject): SQL[Nothing, NoExtractor] = {
    val (bytes, hash, uri, clientId) = obj
    sql"""
        WITH
          existing AS (
            SELECT id FROM objects
            WHERE hash = ${hash}
          ),
          inserted AS (
            INSERT INTO objects (hash, content)
            SELECT ${hash}, ${bytes}
            WHERE NOT EXISTS (SELECT * FROM existing)
            RETURNING id
          )
          INSERT INTO object_urls
          SELECT ${uri}, id, ${clientId}
          FROM (
            SELECT id FROM inserted
            UNION ALL
            SELECT id FROM existing
          )
        """
  }

  def getUpdateSql(oldHash: String, obj: RRDPObject): SQL[Nothing, NoExtractor] = {
    val (bytes, newHash, uri, clientId) = obj
    sql"""
        WITH
          old AS (
            DELETE FROM objects
            WHERE hash = ${oldHash}
            RETURNING id
          ),
          existing AS (
            SELECT id FROM objects
            WHERE hash = ${newHash}
          ),
          new AS (
            INSERT INTO objects (hash, content)
            SELECT ${newHash}, ${bytes}
            WHERE NOT EXISTS (SELECT * FROM existing)
            RETURNING id
          )
          INSERT INTO object_urls
          SELECT ${uri}, id, ${clientId}
          FROM (
            SELECT id FROM inserted
            UNION ALL
            SELECT id FROM existing
          )"""
  }

  private def getDeleteSql(hash: String) : SQL[Nothing, NoExtractor] = {
    sql"""DELETE FROM objects WHERE hash = ${hash}"""
  }

  def clear(implicit session: DBSession): Unit = {
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

  def applyChangesSQL(changeSet: QueryMessage, clientId: ClientId): Unit = {
    val sqls = changeSet.pdus.map {
        case WithdrawQ(_, _, hash) =>
          getDeleteSql(hash)
        case PublishQ(uri, _, None, bytes) =>
          val h = hash(bytes)
          getInsertSql((bytes, h, uri, clientId))
        case PublishQ(uri, _, Some(oldHash), bytes) =>
          val h = hash(bytes)
          getUpdateSql(oldHash, (bytes, h, uri, clientId))
      }

    DB.localTx { implicit session =>
      sqls.foreach(_.execute())
    }
  }

  def check() = ()
}

object PgStore {
  type State = Map[URI, (Bytes, Hash, ClientId)]
}


