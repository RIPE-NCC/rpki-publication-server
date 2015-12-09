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

  val rrdpWriter = wire[RrdpRepositoryWriter]
  val rsyncWriter = wire[RsyncRepositoryWriter]

  protected val deltaStore = DeltaStore.get

  protected val objectStore = ObjectStore.get

  override def receive = {
    case InitCommand(newServerState) =>
      catchExceptions(initFSContent(newServerState))

    case CleanSnapshotsCommand(timestamp, latestSerial) =>
      catchExceptions(cleanupSnapshots(timestamp, latestSerial))

    case WriteCommand(newServerState) =>
      catchExceptions(updateFSContent(newServerState))
  }

  def catchExceptions(f: => Unit) = {
    try { f }
    catch {
      case e: Exception =>
        logger.error("Error processing command", e)
        throw e
    }
  }

  def initFSContent(newServerState: ServerState): Unit = {
    val objects = objectStore.listAll
    val snapshot = Snapshot(newServerState, objects)

    // TODO this could be done concurrently with the rest of the init
    // TODO However, if it fails, it should prevent server from starting
    catchExceptions {
      rsyncWriter.writeSnapshot(snapshot)
    }

    val deltas = deltaStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.unpublishedFileRetainPeriod)
    val (deltasToPublish, deltasToDelete) = deltas.partition(_.whenToDelete.isEmpty)
    val newNotification = Notification.create(snapshot, newServerState, deltasToPublish.toSeq)

    val failures = deltasToPublish.par.map { d =>
      (d, rrdpWriter.writeDelta(conf.rrdpRepositoryPath, d))
    }.collect {
      case (d, Failure(f)) => (d, f)
    }.seq

    if (failures.isEmpty) {
      val now = System.currentTimeMillis
      rrdpWriter.writeNewState(conf.rrdpRepositoryPath, newServerState, newNotification, snapshot) match {
        case Success(timestampOption) =>
          logger.info(s"Written snapshot ${newServerState.serialNumber}")
          if (timestampOption.isDefined) scheduleSnapshotCleanup(timestampOption.get, newServerState.serialNumber)
          cleanupDeltas(deltasToDelete.filter(_.whenToDelete.exists(_.getTime < now)))
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
        rrdpWriter.writeDelta(conf.rrdpRepositoryPath, delta).recover {
          case e: Exception =>
            logger.error(s"Could not write delta $givenSerial", e)
        }
        rsyncWriter.writeDelta(conf.rrdpRepositoryPath, delta)
    }

    objectStore.listAll(givenSerial) match {
      case None =>
        logger.info(s"Skipping snapshot $givenSerial")

      case Some(objects) =>
        logger.info(s"Writing snapshot $givenSerial to filesystem")
        val snapshot = Snapshot(newServerState, objects)

        val deltas = deltaStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.unpublishedFileRetainPeriod)

        val (deltasToPublish, deltasToDelete) = deltas.partition(_.whenToDelete.isEmpty)

        val newNotification = Notification.create(snapshot, newServerState, deltasToPublish.toSeq)
        val now = System.currentTimeMillis
        rrdpWriter.writeNewState(conf.rrdpRepositoryPath, newServerState, newNotification, snapshot) match {
          case Success(timestampOption) =>
            if (timestampOption.isDefined) scheduleSnapshotCleanup(timestampOption.get, givenSerial)
            cleanupDeltas(deltasToDelete.filter(_.whenToDelete.exists(_.getTime < now)))
          case Failure(e) =>
            logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
        }
    }
  }

  def scheduleSnapshotCleanup(timestamp: FileTime, latestSerial: Long): Unit = {
    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod, self, CleanSnapshotsCommand(timestamp, latestSerial))
  }

  def cleanupSnapshots(timestamp: FileTime, latestSerial: Long): Unit = {
    logger.info(s"Removing snapshots older than $timestamp")
    rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, timestamp, latestSerial)
  }

  def cleanupDeltas(deltasToDelete: Iterable[Delta]): Unit = {
    if (deltasToDelete.nonEmpty) {
      logger.info("Removing deltas: " + deltasToDelete.map(_.serial).mkString(","))
      rrdpWriter.deleteDeltas(conf.rrdpRepositoryPath, deltasToDelete)
      deltaStore.delete(deltasToDelete)
    }
  }
}

object FSWriterActor {
  def props = Props(new FSWriterActor())
}
