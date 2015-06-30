package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime

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

      deltaStore.getDelta(newServerState.serialNumber) match {
        case None =>
          logger.error(s"Could not find delta ${newServerState.serialNumber}")
        case Some(delta) =>
          logger.info(s"Writing delta ${newServerState.serialNumber} to filesystem")
          repositoryWriter.writeDelta(conf.locationRepositoryPath, delta).recover {
            case e: Exception =>
              logger.error(s"Could not write delta ${newServerState.serialNumber}", e)
          }
      }

      val now = System.currentTimeMillis

      val objects = objectStore.listAll(newServerState.serialNumber)
      if (objects.isEmpty) {
        logger.info(s"Skipping snapshot ${newServerState.serialNumber}")
      } else {
        logger.info(s"Writing snapshot ${newServerState.serialNumber} to filesystem")
        val snapshot = Snapshot(newServerState, objects)

        val (deltas, accSize, thresholdSerial) = deltaStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.snapshotRetainPeriod)
        logger.info(s"Deltas older than $thresholdSerial will be scheduled for cleansing, the total size of newer deltas is $accSize")
        lazy val deltasToPublish = deltas.filter(_.whenToDelete.isEmpty)
        lazy val deltasToDelete = deltas.filter(_.whenToDelete.exists(_.getTime < now))

        val newNotification = Notification.create(snapshot, newServerState, deltasToPublish)
        repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, newNotification, snapshot) match {
          case Success(Some(timestamp)) =>
            scheduleSnapshotCleanup(timestamp)
            cleanupDeltas(deltasToDelete)
          case Success(None) =>
            logger.info("No previous snapshots to clean")
          case Failure(e) =>
            logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
        }
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
      repositoryWriter.deleteDeltas(conf.locationRepositoryPath, deltasToDelete)
      deltaStore.delete(deltasToDelete)
    }
  }
}

object FSWriterActor {
  def props = Props(new FSWriterActor())
}

