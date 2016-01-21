package net.ripe.rpki.publicationserver.store

import java.util.UUID

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.AppConfig
import net.ripe.rpki.publicationserver.model.ServerState
import slick.dbio.DBIO
import slick.driver.DerbyDriver.api._

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

object Migrations {

  import DB._

  val db = DB.db

  lazy val conf = wire[AppConfig]

  // Migrations are to be added here together
  // with their indexes

  private val migrations = Map (
    1 -> DBIO.seq(objects.schema.create),
    2 -> DBIO.seq(deltas.schema.create),
    // TODO Change migration to Slick instead of native SQL
    4 -> DBIO.seq(sqlu"CREATE INDEX IDX_CLIENT_ID ON #${objects.baseTableRow.tableName}(CLIENT_ID)")
  )

  def migrate() = synchronized {
    Await.result(createTableIfNotExists(migrationsTable), 10.seconds)
    val latestMigration = Await.result(db.run(migrationsTable.map(_.number).max.result), 1.seconds)
    val latest = latestMigration.getOrElse(0)

    migrations.toSeq.filter(_._1 > latest).sortBy(_._1).foreach { x =>
      val (i, action) = x
      val applyMigration = db.run {
        DBIO.seq (
          action,
          migrationsTable += (i, action.toString)
        ).transactionally
      }
      Await.result(applyMigration, conf.defaultTimeout)
    }
  }

  private def createTableIfNotExists(table: TableQuery[_ <: Table[_]]): Future[Unit] = {
     for {
       // Just querying MTable does not seem to work with Derby + Slick
       tableName <- db.run(sql"select TABLENAME from SYS.SYSTABLES where TABLENAME=${table.baseTableRow.tableName}".as[String])
       future <- if (tableName.isEmpty) db.run(table.schema.create) else Future.successful(())
     } yield future
  }

  private class Migration(tag: Tag) extends Table[(Int, String)](tag, "migrations") {
    def number = column[Int]("MIGRATION_NUMBER", O.PrimaryKey)
    def source = column[String]("SOURCE")

    def * = (number, source)
  }

  private lazy val migrationsTable = TableQuery[Migration]
}
