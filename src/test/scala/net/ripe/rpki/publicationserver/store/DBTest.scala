package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.{Base64, Hash, PublicationServerBaseTest}

import scala.concurrent.Await
import scala.concurrent.duration.Duration


class DBTest extends PublicationServerBaseTest {

  import slick.driver.DerbyDriver.api._

  val db = DB.db
  val objectStore = new ObjectStore
  val serverStateStore = new ServerStateStore

  before {
    Migrations.migrate()
    serverStateStore.clear()
    Migrations.initServerState()
    objectStore.clear()
  }

  test("should rollback transaction in case lifted calculation fails") {
    def crash = throw new Exception("I'm dying")

    val clientId = ClientId(UUID.randomUUID().toString)
    val i = objectStore.insertAction(clientId, (Base64("AAAA=="), Hash("jfkfhjghj"), new URI("rsync://host.com/path")))
    try {
      Await.result(db.run(
        DBIO.seq(i, DB.liftDB(crash)).transactionally
      ), Duration(1, TimeUnit.MINUTES))
      fail("We should not be here")
    } catch {
      case e: Throwable => // that should happen
    }

    objectStore.listAll(serverStateStore.get.serialNumber).get should have size 0

    Await.result(db.run(DBIO.seq(i).transactionally), Duration(1, TimeUnit.MINUTES))
    objectStore.listAll(serverStateStore.get.serialNumber).get should have size 1
  }

}
