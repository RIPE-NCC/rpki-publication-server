package net.ripe.rpki.publicationserver.store.fs

import akka.actor.{Actor, Props}
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.DB
import net.ripe.rpki.publicationserver.{Logging, Urls}

import scala.util.{Success, Failure}

case class WriteCommand(newServerState: ServerState, objects: Seq[DB.RRDPObject], deltas: Seq[Delta])

class FSWriterActor extends Actor with Logging with Urls {

  val repositoryWriter = wire[RepositoryWriter]

  override def receive = {
    case WriteCommand(newServerState: ServerState, objects: Seq[DB.RRDPObject], deltas: Seq[Delta]) =>
      logger.info("Writing snapshot and delta's to filesystem")

      val snapshot = Snapshot(newServerState, objects)
      val newNotification = Notification.create(snapshot, newServerState, deltas)
      repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, deltas, newNotification, snapshot) match {
        case Success(timestamp) =>
          // TODO cleanup snapshots
        case Failure(e) =>
          logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
      }
  }
}

object FSWriterActor {
  def props = Props(new FSWriterActor())
}

