package net.ripe.rpki.publicationserver.store

import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ServerStateStore(db: DB.DBType) extends ServerStateDB {

  import DB._

  override def get: ServerState = {
    val selectFirst = serverStates.take(1).result
    val f = db.run(selectFirst)
    Await.result(f, Duration.Inf).head
  }

  override def update(serverState: ServerState): Unit = {
    val update = serverStates.update(serverState)
    val f = db.run(update)
    Await.result(f, Duration.Inf)
  }
}
