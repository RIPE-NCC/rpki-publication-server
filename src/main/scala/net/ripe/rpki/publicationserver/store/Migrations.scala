package net.ripe.rpki.publicationserver.store

import slick.dbio.DBIO
import slick.driver.H2Driver.api._

object Migrations {

  import DB._

  val v1 = DBIO.seq {
    objects.schema.create
  }

  val migrations = Map(1 -> v1)

  def migrate() = {
    createMigrationTableIfNeeded
    migrations.toSeq.sortBy(_._1).foreach { x =>
      val (i, action) = x

    }
  }

  private def createMigrationTableIfNeeded = {

  }

  class Migration(tag: Tag) extends Table[(Int, String)](tag, "migrations") {
    def number = column[Int]("MIGRATION_NUMBER", O.PrimaryKey)
    def source = column[String]("SOURCE")
    def * = (number, source)
  }

  val migrationTables = TableQuery[Migration]


}
