package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.{Hash, Base64, PublicationServerBaseTest}

import scala.concurrent.Await
import scala.concurrent.duration.Duration


class DBTest extends PublicationServerBaseTest {

  import slick.driver.H2Driver.api._

  val db = DB.db
  val objectStore = new ObjectStore

  before {
    Migrations.migrate
    objectStore.clear()
  }

  test("should rollback transaction in case lifted calculation failed") {
    def crash = throw new Exception("I'm dying")

    val clientId = ClientId(UUID.randomUUID().toString)
    val i = objectStore.insertAction(clientId, (Base64("AAAA=="), Hash("jfkfhjghj"), new URI("rsync://host.com/path")))
    try {
      Await.result(db.run(
        DBIO.seq(i, DB.liftDB(crash)).transactionally
      ), Duration.Inf)
      fail("We should not be here")
    } catch {
      case e: Throwable => // that should happen
    }

    objectStore.listAll should have size 0

    Await.result(db.run(DBIO.seq(i).transactionally), Duration.Inf)
    objectStore.listAll should have size 1
  }

}
