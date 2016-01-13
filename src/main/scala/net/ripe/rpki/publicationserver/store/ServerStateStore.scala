package net.ripe.rpki.publicationserver.store

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.AppConfig
import net.ripe.rpki.publicationserver.model.ServerState
import slick.driver.DerbyDriver.api._

import scala.concurrent.Await

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

  def getLastSynced: BigInt = getAttr("LAST_SYNCED_UPDATE_NUMBER", 0, BigInt(_))

  def getSerial: Long = getAttr("SERIAL", 0, _.toLong)

  def getSessionId: String = getAttr("SESSION_ID", "", identity)

  private def getAttr[T](attrName: String, default: => T, parse: String => T) = {
    val q = attributes.filter(_.name === attrName)
    Await.result(db.run(q.result), conf.defaultTimeout) match {
      case Seq() => default
      case Seq((_, x)) => parse(x)
    }
  }

  def updateAction(serverState: ServerState) = serverStates.update(serverState)

  def clear() = Await.result(db.run(serverStates.delete), conf.defaultTimeout)

}

object ServerStateStore {
  def get = new ServerStateStore
}