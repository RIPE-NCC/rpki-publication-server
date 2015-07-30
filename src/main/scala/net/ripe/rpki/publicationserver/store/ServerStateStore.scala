package net.ripe.rpki.publicationserver.store

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.AppConfig
import net.ripe.rpki.publicationserver.model.ServerState
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

class ServerStateStore {

  lazy val conf = wire[AppConfig]

  import DB._

  val db = DB.db

  def get: ServerState = {
    val selectFirst = serverStates.take(1).result
    val f = db.run(selectFirst)
    Await.result(f, conf.defaultTimeout).head
  }

  def update(serverState: ServerState): Unit = {
    val update = serverStates.update(serverState)
    val f = db.run(update)
    Await.result(f, conf.defaultTimeout)
  }

  def updateAction(serverState: ServerState) = serverStates.update(serverState)

  def clear() = Await.result(db.run(serverStates.delete), conf.defaultTimeout)
}
