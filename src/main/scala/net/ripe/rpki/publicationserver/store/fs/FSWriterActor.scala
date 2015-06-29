package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime
import java.util.Date

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.{ObjectStore, DeltaStore, DB}
import net.ripe.rpki.publicationserver.{Logging, Config}

import scala.util.{Failure, Success}

case class WriteCommand(newServerState: ServerState)

class FSWriterActor extends Actor with Logging with Config {

  val repositoryWriter = wire[RepositoryWriter]

  private val deltaStore = DeltaStore.get

  private val objectStore = ObjectStore.get

  override def receive = {
    case WriteCommand(newServerState: ServerState) =>
      logger.info("Writing snapshot and delta's to filesystem")
      val now = new Date().getTime

      val objects = objectStore.listAll
      val snapshot = Snapshot(newServerState, objects)

      val deltas = deltaStore.checkDeltaSetSize(snapshot.binarySize, conf.snapshotRetainPeriod)
      lazy val deltasToPublish = deltas.filter(_.whenToDelete.isEmpty)
      lazy val deltasToDelete = deltas.filter(_.whenToDelete.exists(_.getTime < now))

      val newNotification = Notification.create(snapshot, newServerState, deltasToPublish)
      repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, deltasToPublish, newNotification, snapshot) match {
        case Success(Some(timestamp)) =>
          logger.info(s"Removing snapshots older than $timestamp")
          repositoryWriter.deleteSnapshotsOlderThan(conf.locationRepositoryPath, applyRetainPeriod(timestamp))
          if (deltasToDelete.nonEmpty) {
            deltaStore.delete(deltasToDelete)
            repositoryWriter.deleteDeltas(conf.locationRepositoryPath, deltasToDelete)
          }
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

