package net.ripe.rpki.publicationserver.store

import slick.dbio.DBIO
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration._

object Migrations {

  import DB._

  val db = DB.db

  // Migrations are to be added here together
  // with their indexes

  private val migrations = Map (
    1 -> DBIO.seq(objects.schema.create),
    2 -> DBIO.seq(deltas.schema.create),
    3 -> DBIO.seq(serverStates.schema.create)
  )

  def migrate = synchronized {
    createMigrationTableIfNeeded
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
  }

  private def createMigrationTableIfNeeded = {
    if (!tableExists(db, "migrations")) {
      Await.result(db.run(migrationsTable.schema.create), 10.seconds)
    }
  }

  private class Migration(tag: Tag) extends Table[(Int, String)](tag, "migrations") {
    def number = column[Int]("MIGRATION_NUMBER", O.PrimaryKey)
    def source = column[String]("SOURCE")

    def * = (number, source)
  }

  private lazy val migrationsTable = TableQuery[Migration]

}
