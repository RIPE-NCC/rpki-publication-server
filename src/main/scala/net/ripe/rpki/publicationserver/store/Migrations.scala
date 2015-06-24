package net.ripe.rpki.publicationserver.store

import java.util.UUID

import net.ripe.rpki.publicationserver.model.ServerState
import slick.dbio.DBIO
import slick.driver.H2Driver.api._
import slick.jdbc.meta.MTable

import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Migrations {

  import DB._

  val db = DB.db

  // Migrations are to be added here together
  // with their indexes

  private val migrations = Map (
    1 -> DBIO.seq(objects.schema.create),
    2 -> DBIO.seq(deltas.schema.create),
    3 -> DBIO.seq(serverStates.schema.create),
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
      Await.result(applyMigration, Duration.Inf)
    }

    initServerState()
  }

  private def createTableIfNotExists(table: TableQuery[_ <: Table[_]]) =
    db.run(MTable.getTables(table.baseTableRow.tableName)).flatMap { result =>
      if (result.isEmpty)
        db.run(table.schema.create)
      else
        Future.successful(())
    }


  private class Migration(tag: Tag) extends Table[(Int, String)](tag, "migrations") {
    def number = column[Int]("MIGRATION_NUMBER", O.PrimaryKey)
    def source = column[String]("SOURCE")

    def * = (number, source)
  }

  private lazy val migrationsTable = TableQuery[Migration]

  def initServerState() = {
    val insert = { serverStates += ServerState(UUID.randomUUID(), 1L) }
    val nothing = DBIO.seq()

    val insertIfEmtpy = for {
       states <- serverStates.result
       _ <- if (states.isEmpty) insert else nothing
    } yield ()

    val f = db.run(insertIfEmtpy.transactionally)
    Await.result(f, Duration.Inf)
  }
}
