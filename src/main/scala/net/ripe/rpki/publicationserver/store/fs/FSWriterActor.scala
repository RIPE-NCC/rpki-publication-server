package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime
import java.util.Date

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.{DeltaStore, ObjectStore}
import net.ripe.rpki.publicationserver.{Logging, Urls}

import scala.util.{Failure, Success}

case class WriteCommand(newServerState: ServerState)

class FSWriterActor extends Actor with Logging with Urls {

  val repositoryWriter = wire[RepositoryWriter]
  lazy val objectStore = new ObjectStore
  lazy val deltaStore = DeltaStore.get

  override def receive = {
    case WriteCommand(newServerState) =>
      logger.info("Writing snapshot and delta's to filesystem")
      updateFS(newServerState)
  }

  def applyRetainPeriod(timestamp: FileTime) = FileTime.from(timestamp.toInstant.minusMillis(conf.snapshotRetainPeriod))

  def updateFS(newServerState: ServerState) = {
    val snapshot = Snapshot(newServerState, objectStore.listAll)
    val deltas = deltaStore.checkDeltaSetSize(snapshot.binarySize)

    val now = new Date().getTime

    val newNotification = Notification.create(snapshot, newServerState, deltas)
    repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, deltas, newNotification, snapshot) match {
      case Success(Some(timestamp)) =>
        val cleanupTimestamp = applyRetainPeriod(timestamp)
        logger.info(s"Removing snapshots older than $cleanupTimestamp")
        repositoryWriter.deleteSnapshotsOlderThan(conf.locationRepositoryPath, cleanupTimestamp)
      case Success(None) =>
        logger.info("No previous snapshots to clean")
      case Failure(e) =>
        logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
    }

    val deltasToDelete = deltas.filter(_.whenToDelete.exists(_.getTime < now))
    if (deltasToDelete.nonEmpty) {
      logger.info("Removing deltas from DB and filesystem")
      deltaStore.delete(deltas)
      repositoryWriter.deleteDeltas(conf.locationRepositoryPath, deltas)
    }
  }
}

object FSWriterActor {
  def props = Props(new FSWriterActor())
}
