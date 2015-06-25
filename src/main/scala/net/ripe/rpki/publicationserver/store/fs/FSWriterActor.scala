package net.ripe.rpki.publicationserver.store.fs

import akka.actor.{Props, Actor}
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.{NotificationState, Urls, Logging}
import net.ripe.rpki.publicationserver.model.{Notification, Snapshot, Delta, ServerState}
import net.ripe.rpki.publicationserver.store.DB

import scala.util.{Failure, Success}

case class WriteCommand(newServerState: ServerState, objects: Seq[DB.RRDPObject], deltas: Seq[Delta])

class FSWriterActor extends Actor with Logging with Urls {

  val repositoryWriter = wire[RepositoryWriter]

  val notificationState = wire[NotificationState]

  override def receive = {
    case WriteCommand(newServerState: ServerState, objects: Seq[DB.RRDPObject], deltas: Seq[Delta]) =>
      logger.info("Writing snapshot and delta's to filesystem")

      val snapshot = Snapshot(newServerState, objects)
      val newNotification = Notification.create(snapshot, newServerState, deltas)
      repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, deltas, newNotification, snapshot) match {
        case Success(_) =>
          notificationState.update(newNotification)
        case Failure(e) =>
          logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
      }
  }
}

object FSWriterActor {
  def props = Props(new FSWriterActor())
}

