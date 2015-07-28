package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.{DeltaStore, ObjectStore}
import net.ripe.rpki.publicationserver.{Config, Logging}

import scala.util.{Failure, Success}


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
    val givenSerial = newServerState.serialNumber

    deltaStore.getDelta(givenSerial) match {
      case None =>
        logger.error(s"Could not find delta $givenSerial")
      case Some(delta) =>
        logger.info(s"Writing delta $givenSerial to filesystem")
        repositoryWriter.writeDelta(conf.locationRepositoryPath, delta).recover {
          case e: Exception =>
            logger.error(s"Could not write delta $givenSerial", e)
        }
    }


    objectStore.listAll(givenSerial) match {
      case None =>
        logger.info(s"Skipping snapshot $givenSerial")

      case Some(objects) =>
        logger.info(s"Writing snapshot $givenSerial to filesystem")
        val snapshot = Snapshot(newServerState, objects)

        val deltas = deltaStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.snapshotRetainPeriod)

        val (deltasToPublish, deltasToDelete) = deltas.partition(_.whenToDelete.isEmpty)

        val newNotification = Notification.create(snapshot, newServerState, deltasToPublish.toSeq)
        val now = System.currentTimeMillis
        repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, newNotification, snapshot) match {
          case Success(Some(timestamp)) =>
            scheduleSnapshotCleanup(timestamp, givenSerial)
            cleanupDeltas(deltasToDelete.filter(_.whenToDelete.exists(_.getTime < now)))
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
  
  def cleanupDeltas(deltasToDelete: Iterable[Delta]): Unit = {
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

