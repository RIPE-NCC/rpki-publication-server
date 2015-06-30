package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.{Base64, Hash, PublicationServerBaseTest}

import scala.concurrent.Await
import scala.concurrent.duration.Duration


class DBTest extends PublicationServerBaseTest {

  import slick.driver.H2Driver.api._

  val db = DB.db
  val objectStore = new ObjectStore
  val serverStateStore = new ServerStateStore

  before {
    Migrations.migrate()
    serverStateStore.clear()
    Migrations.initServerState()
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

    objectStore.listAll(serverStateStore.get.serialNumber) should have size 0

    Await.result(db.run(DBIO.seq(i).transactionally), Duration.Inf)
    objectStore.listAll(serverStateStore.get.serialNumber) should have size 1
  }

}
