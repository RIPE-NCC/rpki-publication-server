package net.ripe.rpki.publicationserver.store

import net.ripe.rpki.publicationserver.PublicationServerBaseSpec
import net.ripe.rpki.publicationserver.model.ServerState

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class MigrationsTest extends PublicationServerBaseSpec {

  import DB._

  test("should have inserted an initial row in an empty serverSettings table") {

    //val states: Seq[ServerState] = Await.result(db.run(serverStates.result), Duration.Inf)
    val states: Seq[ServerState] = Await.result(db.run(Migrations.getServerStates), Duration.Inf)

    states.size should be(1)
    states.head.serialNumber should be(1L)
  }

  test("should not add a second initial row") {
    Migrations.initServerState()

    val states: Seq[ServerState] = Await.result(db.run(Migrations.getServerStates), Duration.Inf)

    states.size should be(1)
    states.head.serialNumber should be(1L)
  }
}
