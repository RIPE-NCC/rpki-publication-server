package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.DB
import net.ripe.rpki.publicationserver.{Logging, Urls}

import scala.util.{Failure, Success}

case class WriteCommand(newServerState: ServerState, objects: Seq[DB.RRDPObject], deltas: Seq[Delta])

class FSWriterActor extends Actor with Logging with Urls {

  val repositoryWriter = wire[RepositoryWriter]

  override def receive = {
    case WriteCommand(newServerState: ServerState, objects: Seq[DB.RRDPObject], deltas: Seq[Delta]) =>
      logger.info("Writing snapshot and delta's to filesystem")

      val snapshot = Snapshot(newServerState, objects)
      val newNotification = Notification.create(snapshot, newServerState, deltas)
      repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, deltas, newNotification, snapshot) match {
        case Success(Some(timestamp)) =>
          logger.info(s"Removing snapshots older than $timestamp")
          repositoryWriter.deleteSnapshotsOlderThan(conf.locationRepositoryPath, applyRetainPeriod(timestamp))
        case Success(None) =>
          logger.info("No previous snapshots to clean")
        case Failure(e) =>
          logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
      }
  }

  def applyRetainPeriod(timestamp: FileTime) = FileTime.from(timestamp.toInstant.minusMillis(conf.snapshotRetainPeriod))
}

object FSWriterActor {
  def props = Props(new FSWriterActor())
}

