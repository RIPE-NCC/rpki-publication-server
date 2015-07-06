package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.Path
import java.nio.file.attribute.FileTime

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.{DeltaStore, ObjectStore}
import net.ripe.rpki.publicationserver.{Config, Logging}

import scala.util.{Try, Failure, Success}


case class InitCommand(newServerState: ServerState)
case class WriteCommand(newServerState: ServerState)
case class CleanSnapshotsCommand(timestamp: FileTime, latestSerial: Long)

class FSWriterActor extends Actor with Logging with Config {
  import context._

  val repositoryWriter = wire[RepositoryWriter]

  protected val deltaStore = DeltaStore.get

  protected val objectStore = ObjectStore.get

  override def receive = {
    case InitCommand(newServerState) =>
      initFSContent(newServerState)

    case CleanSnapshotsCommand(timestamp, latestSerial) =>
      cleanupSnapshots(timestamp, latestSerial)

    case WriteCommand(newServerState) =>
      updateFSContent(newServerState)
  }

  def initFSContent(newServerState: ServerState): Unit = {
    val objects = objectStore.listAll
    val deltas = deltaStore.getDeltas
    val snapshot = Snapshot(newServerState, objects)
    val newNotification = Notification.create(snapshot, newServerState, deltas)

    val failures = deltas.par.map { d =>
      (d, repositoryWriter.writeDelta(conf.locationRepositoryPath, d))
    }.collect {
      case (d, Failure(f)) => (d, f)
    }.seq

    if (failures.isEmpty) {
      repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, newNotification, snapshot) match {
        case Success(_) =>
          logger.info(s"Written snapshot ${newServerState.serialNumber}")
        case Failure(e) =>
          logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
      }
    } else {
      failures.foreach { x =>
        val (d, f) = x
        logger.info(s"Error occurred while writing a delta ${d.serial}: $f")
      }
    }

  }

  def updateFSContent(newServerState: ServerState): Unit = {
    val latestSerial = newServerState.serialNumber

    deltaStore.getDelta(latestSerial) match {
      case None =>
        logger.error(s"Could not find delta $latestSerial")
      case Some(delta) =>
        logger.info(s"Writing delta $latestSerial to filesystem")
        repositoryWriter.writeDelta(conf.locationRepositoryPath, delta).recover {
          case e: Exception =>
            logger.error(s"Could not write delta $latestSerial", e)
        }
    }

    val now = System.currentTimeMillis

    objectStore.listAll(latestSerial) match {
      case None =>
        logger.info(s"Skipping snapshot $latestSerial")

      case Some(objects) =>
        logger.info(s"Writing snapshot $latestSerial to filesystem")
        val snapshot = Snapshot(newServerState, objects)

        val (deltas, accSize, thresholdSerial) = deltaStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.snapshotRetainPeriod)
        thresholdSerial.foreach { lastSerial =>
          logger.info(s"Deltas older than $lastSerial will be scheduled for cleansing, the total size of newer deltas is $accSize")
        }
        lazy val deltasToPublish = deltas.filter(_.whenToDelete.isEmpty)
        lazy val deltasToDelete = deltas.filter(_.whenToDelete.exists(_.getTime < now))

        val newNotification = Notification.create(snapshot, newServerState, deltasToPublish)
        repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, newNotification, snapshot) match {
          case Success(Some(timestamp)) =>
            scheduleSnapshotCleanup(timestamp, latestSerial)
            cleanupDeltas(deltasToDelete)
          case Success(None) =>
            logger.info("No previous snapshots to clean")
          case Failure(e) =>
            logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
        }
    }
  }

  def scheduleSnapshotCleanup(timestamp: FileTime, latestSerial: Long): Unit = {
    system.scheduler.scheduleOnce(conf.snapshotRetainPeriod, self, CleanSnapshotsCommand(timestamp, latestSerial))
  }

  def cleanupSnapshots(timestamp: FileTime, latestSerial: Long): Unit = {
    logger.info(s"Removing snapshots older than $timestamp")
    repositoryWriter.deleteSnapshotsOlderThan(conf.locationRepositoryPath, timestamp, latestSerial)
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

