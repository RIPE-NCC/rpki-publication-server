package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.attribute.FileTime

import akka.actor._
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.{UpdateStore, ObjectStore, ServerStateStore}
import net.ripe.rpki.publicationserver.{Config, Logging}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.control.ControlThrowable
import scala.util.{Failure, Success, Try}


case class InitCommand(newServerState: ServerState)
case class WriteCommand(newServerState: ServerState)
case class CleanSnapshotsCommand(timestamp: FileTime)

case class UpdateSnapsot()
case class SetTarget(actor: ActorRef)


class FSWriterActor extends Actor with Logging with Config {
  import context._

  lazy val rrdpWriter = wire[RrdpRepositoryWriter]
  lazy val rsyncWriter = wire[RsyncRepositoryWriter]

  protected val updateStore = UpdateStore.get

  protected val objectStore = ObjectStore.get

  protected val serverStateStore = ServerStateStore.get

  override def receive = {
    case InitCommand(newServerState) =>
      initFSContent(newServerState)

    case WriteCommand(newServerState) =>
      tryTo(updateFSContent(newServerState))

    case UpdateSnapsot() =>
      tryTo(updateFSSnapshot())

    case CleanSnapshotsCommand(timestamp) =>
      tryTo(cleanupSnapshots(timestamp))
  }

  def tryTo[T](f: => T) = Try(f).failed.foreach {
    logger.error("Error processing command", _)
  }

  def throwFatalException = {
    logger.error("Error in repository init, bailing out")
    // ThreadDeath is one of the few exceptions that Akka considers fatal, i.e. which can trigger jvm termination
    throw new ThreadDeath
  }

  def initFSContent(newServerState: ServerState): Unit = {
    val objects = objectStore.listAll
    val snapshot = Snapshot(newServerState, objects)

    val rsync = Future {
      try rsyncWriter.writeSnapshot(snapshot) catch {
        case e: Throwable =>
          logger.error(s"Error occurred while syncing rsync repository", e)
          throwFatalException
      }
    }

    try {
      val deltas = updateStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.unpublishedFileRetainPeriod)
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
            if (timestampOption.isDefined)
              scheduleSnapshotCleanup(timestampOption.get)
            cleanupDeltas(deltasToDelete.filter(_.whenToDelete.exists(_.getTime < now)))
          case Failure(e) =>
            logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
            throwFatalException
        }
      } else {
        failures.foreach { x =>
          val (d, f) = x
          logger.error(s"Error occurred while writing a delta ${d.serial}: $f")
        }
        throwFatalException
      }
    } finally {
      Await.result(rsync, 10.minutes)
    }
  }

  def updateFSContent(newServerState: ServerState): Unit = {
    val givenSerial = newServerState.serialNumber

    updateFSDelta(givenSerial)
    scheduleFSSnapshotUpdate(newServerState)
  }

  def updateFSDelta(givenSerial: Long) = {
    updateStore.getDelta(givenSerial) match {
      case None =>
        logger.error(s"Could not find delta $givenSerial")
      case Some(delta) =>
        logger.debug(s"Writing delta $givenSerial to rsync filesystem")
        rsyncWriter.writeDelta(delta).recover {
          case e: Exception =>
            logger.error(s"Could not write delta $givenSerial to rsync repo: ", e)
        }
        logger.debug(s"Writing delta $givenSerial to RRDP filesystem")
        rrdpWriter.writeDelta(conf.rrdpRepositoryPath, delta).recover {
          case e: Exception =>
            logger.error(s"Could not write delta $givenSerial to RRDP repo: ", e)
        }
    }
  }


  def updateFSSnapshot(): Unit = {
    val serverState = serverStateStore.get
    val objects = objectStore.listAll

    logger.info(s"Writing snapshot ${serverState.serialNumber} to filesystem")
    val snapshot = Snapshot(serverState, objects)

    val deltas = updateStore.markOldestDeltasForDeletion(snapshot.binarySize, conf.unpublishedFileRetainPeriod)

    val (deltasToPublish, deltasToDelete) = deltas.partition(_.whenToDelete.isEmpty)
    val newNotification = Notification.create(snapshot, serverState, deltasToPublish.toSeq)
    rrdpWriter.writeNewState(conf.rrdpRepositoryPath, serverState, newNotification, snapshot) match {
      case Success(timestampOption) =>
        timestampOption.foreach(scheduleSnapshotCleanup)
        val now = System.currentTimeMillis
        cleanupDeltas(deltasToDelete.filter(_.whenToDelete.exists(_.getTime < now)))
      case Failure(e) =>
        logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
    }
  }

  var snapshotFSCleanupScheduled = false

  def scheduleSnapshotCleanup(timestamp: FileTime): Unit = {
    if (!snapshotFSCleanupScheduled) {
      system.scheduler.scheduleOnce(snapshotCleanInterval, new Runnable() {
        override def run() = {
          val command = CleanSnapshotsCommand(timestamp)
          self ! command
          logger.debug(s"$command has been sent")
          snapshotFSCleanupScheduled = false
        }
      })
      snapshotFSCleanupScheduled = true
    }
  }

  def snapshotCleanInterval: FiniteDuration = {
    val i = conf.unpublishedFileRetainPeriod / 10
    if (i < 1.second) 1.second else i
  }

  var snapshotFSSyncScheduled = false

  def scheduleFSSnapshotUpdate(newServerState: ServerState): Unit = {
    if (!snapshotFSSyncScheduled) {
      logger.info(s"Scheduling snapshot sync for $newServerState")
      system.scheduler.scheduleOnce(conf.snapshotSyncDelay, new Runnable {
        override def run() = {
          self ! UpdateSnapsot()
          logger.info("UpdateSnapshot sent")
          snapshotFSSyncScheduled = false
        }
      })
      snapshotFSSyncScheduled = true
    }
  }


  def cleanupSnapshots(timestamp: FileTime): Unit = {
    logger.info(s"Removing snapshots older than $timestamp")
    val serverState = serverStateStore.get
    rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, timestamp, serverState.serialNumber)
  }

  def cleanupDeltas(deltasToDelete: Iterable[Delta]): Unit = {
    if (deltasToDelete.nonEmpty) {
      logger.info("Removing deltas: " + deltasToDelete.map(_.serial).mkString(","))
      rrdpWriter.deleteDeltas(conf.rrdpRepositoryPath, deltasToDelete)
      updateStore.delete(deltasToDelete)
    }
  }

}

object FSWriterActor {
  def props() = Props(new FSWriterActor())
}

class InitialisationException extends Exception with ControlThrowable
