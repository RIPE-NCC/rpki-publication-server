package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime
import java.util.Date

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.{DeltaStore, ObjectStore}
import net.ripe.rpki.publicationserver.{Config, Logging}

import scala.util.{Failure, Success}


case class WriteCommand(newServerState: ServerState)
case class CleanSnapshotsCommand(timestamp: FileTime)

class FSWriterActor extends Actor with Logging with Config {
  import context._

  val repositoryWriter = wire[RepositoryWriter]

  private val deltaStore = DeltaStore.get

  private val objectStore = ObjectStore.get

  override def receive = {
    case CleanSnapshotsCommand(timestamp) =>
      cleanupSnapshots(timestamp)
    case WriteCommand(newServerState: ServerState) =>
      logger.info("Writing snapshot and delta to filesystem")
      val now = System.currentTimeMillis

      val objects = objectStore.listAll
      val snapshot = Snapshot(newServerState, objects)

      val deltas = deltaStore.checkDeltaSetSize(snapshot.binarySize, conf.snapshotRetainPeriod)
      lazy val deltasToPublish = deltas.filter(_.whenToDelete.isEmpty)
      lazy val deltasToDelete = deltas.filter(_.whenToDelete.exists(_.getTime < now))

      val newNotification = Notification.create(snapshot, newServerState, deltasToPublish)
      repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, deltasToPublish, newNotification, snapshot) match {
        case Success(Some(timestamp)) =>
          scheduleSnapshotCleanup(timestamp)
          cleanupDeltas(deltasToDelete)
        case Success(None) =>
          logger.info("No previous snapshots to clean")
        case Failure(e) =>
          logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
      }
  }

  def scheduleSnapshotCleanup(timestamp: FileTime): Unit = {
    system.scheduler.scheduleOnce(conf.snapshotRetainPeriod, self, CleanSnapshotsCommand(timestamp))
  }

  def cleanupSnapshots(timestamp: FileTime): Unit = {
    logger.info(s"Removing snapshots older than $timestamp")
    repositoryWriter.deleteSnapshotsOlderThan(conf.locationRepositoryPath, timestamp)
  }
  
  def cleanupDeltas(deltasToDelete: => Seq[Delta]): Unit = {
    if (deltasToDelete.nonEmpty) {
      logger.info("Removing deltas: " + deltasToDelete.map(_.serial).mkString(","))
      deltaStore.delete(deltasToDelete)
      repositoryWriter.deleteDeltas(conf.locationRepositoryPath, deltasToDelete)
    }
  }
}

object FSWriterActor {
  def props = Props(new FSWriterActor())
}

