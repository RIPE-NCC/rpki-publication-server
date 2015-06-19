package net.ripe.rpki.publicationserver.store

import net.ripe.rpki.publicationserver.model.ServerState
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ServerStateStore {

  import DB._

  val db = DB.db

  def get: ServerState = {
    val selectFirst = serverStates.take(1).result
    val f = db.run(selectFirst)
    Await.result(f, Duration.Inf).head
  }

  def update(serverState: ServerState): Unit = {
    val update = serverStates.update(serverState)
    val f = db.run(update)
    Await.result(f, Duration.Inf)
  }
}
