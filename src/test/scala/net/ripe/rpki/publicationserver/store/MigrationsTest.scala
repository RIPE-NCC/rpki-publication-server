package net.ripe.rpki.publicationserver.store

import java.util.concurrent.TimeUnit

import net.ripe.rpki.publicationserver.PublicationServerBaseTest
import net.ripe.rpki.publicationserver.model.ServerState

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import slick.driver.H2Driver.api._

class MigrationsTest extends PublicationServerBaseTest {

  import DB._

  val serverStatesStore = new ServerStateStore

  test("should insert an initial row in an empty serverSettings table") {
    serverStatesStore.clear()

    Migrations.initServerState()
    val states = Await.result(db.run(serverStates.result), Duration(1, TimeUnit.MINUTES))

    states.size should be(1)
    states.head.serialNumber should be(1L)
  }

  test("should not add a second initial row") {
    Migrations.initServerState()

    val states: Seq[ServerState] = Await.result(db.run(serverStates.result), Duration(1, TimeUnit.MINUTES))

    states.size should be(1)
    states.head.serialNumber should be(1L)
  }
}
