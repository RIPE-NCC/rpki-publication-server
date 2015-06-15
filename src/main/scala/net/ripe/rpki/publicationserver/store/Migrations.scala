package net.ripe.rpki.publicationserver.store

import slick.dbio.DBIO
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration._

object Migrations {

  import DB._

  private val migrations = Map(1 -> DBIO.seq {
    objects.schema.create
  })

  def migrate(db: Database) = {

    createMigrationTableIfNeeded(db)
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

  private def createMigrationTableIfNeeded(db: Database) = {
    if (!tableExists(db, "migrations")) {
      migrationsTable.schema.create
    }
  }

  private class Migration(tag: Tag) extends Table[(Int, String)](tag, "migrations") {
    def number = column[Int]("MIGRATION_NUMBER", O.PrimaryKey)
    def source = column[String]("SOURCE")

    def * = (number, source)
  }

  private val migrationsTable = TableQuery[Migration]


}
