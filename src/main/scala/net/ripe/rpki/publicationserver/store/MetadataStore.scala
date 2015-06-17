package net.ripe.rpki.publicationserver.store

import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class MetadataStore extends MetadataDB {

  import DB._

  val db = Database.forConfig("h2mem1")

  override def get: Metadatum = {
    val selectFirst = metadata.take(1).result
    val f = db.run(selectFirst)
    Await.result(f, Duration.Inf).head
  }

  override def update(metadata: Metadatum): Unit = ???
}
