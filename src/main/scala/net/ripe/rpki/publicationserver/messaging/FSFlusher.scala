package net.ripe.rpki.publicationserver.messaging

import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.{Date, UUID}

import akka.actor.{Actor, ActorRef, Props}
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.fs.RrdpRepositoryWriter

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

object FSFlusher {
  def props(conf: AppConfig) = Props(new FSFlusher(conf))
}

class FSFlusher(conf: AppConfig) extends Actor with Logging {

  import context._

  protected lazy val rrdpWriter = wire[RrdpRepositoryWriter]

  private var deltas = mutable.Queue[(Long, Hash, Int, Instant)]()
  private var deltasTotalSize = 0L

  val sessionId = UUID.randomUUID()

  private var serial = 1L

  private var dataCleaner: ActorRef = _

  override def preStart() = {
    dataCleaner = context.actorOf(Cleaner.props(conf))
  }

  def throwFatalException = {
    logger.error("Error in repository init, bailing out")
    // ThreadDeath is one of the few exceptions that Akka considers fatal, i.e. which can trigger jvm termination
    throw new ThreadDeath
  }

  override def receive: Receive = {
    case BatchMessage(messages, state) =>
      updateFS(messages, state)
      serial += 1
    case InitRepo(state) =>
      serial = 1L
      rrdpWriter.cleanRepository(conf.rrdpRepositoryPath)
      initFS(state)
      serial += 1
  }


  def initFS(state: ObjectStore.State) = {
    val serverState = ServerState(sessionId, serial)
    val snapshotPdus = state.map { e =>
      val (uri, (base64, _, _)) = e
      (base64, uri)
    }.toSeq

    val snapshot = Snapshot(serverState, snapshotPdus)
    val notification = Notification.create(conf)(snapshot, serverState, Seq())

    rrdpWriter.writeNewState(conf.rrdpRepositoryPath, serverState, notification, snapshot)
    .recover {
      case e: Exception =>
        logger.error(s"Could not write snapshot to rrdp repo: ", e)
        throwFatalException
    }
  }

  def updateFS(messages: Seq[QueryMessage], state: ObjectStore.State) = {
    val pdus = messages.flatMap(_.pdus)
    val delta = Delta(sessionId, serial, pdus)
    deltas.enqueue((serial, delta.contentHash, delta.binarySize, Instant.now()))
    deltasTotalSize += delta.binarySize

    val serverState = ServerState(sessionId, serial)
    val snapshotPdus = state.map { e =>
      val (uri, (base64, _, _)) = e
      (base64, uri)
    }.toSeq

    val snapshot = Snapshot(serverState, snapshotPdus)
    val deltasToDeleteFromFS = deleteExtraDeltas(snapshot.binarySize)
    val notification = Notification.create(conf)(snapshot, serverState, deltas.map(e => (e._1, e._2)))

    Try {
      logger.info(s"Writing delta $serial to RRDP filesystem")
      rrdpWriter.writeDelta(conf.rrdpRepositoryPath, delta)
    } flatMap { _ =>
      logger.info(s"Writing snapshot $serial to RRDP filesystem")
      rrdpWriter.writeNewState(conf.rrdpRepositoryPath, serverState, notification, snapshot)
    } match {
      case Success(timestampOption) =>
        timestampOption.foreach(scheduleSnapshotCleanup(serial))
        if (deltasToDeleteFromFS.nonEmpty) {
          scheduleDeltaCleanups(deltasToDeleteFromFS)
        }
      case Failure(e) =>
        logger.error("Could not update RRDP files: ", e)
    }

  }

  private def waitFor[T](f: Future[T]) = Await.result(f, 10.minutes)

  def snapshotCleanInterval = {
    val i = conf.unpublishedFileRetainPeriod / 10
    if (i < 1.second) 1.second else i
  }

  def afterRetainPeriod = new Date(System.currentTimeMillis() + conf.unpublishedFileRetainPeriod.toMillis)

  var snapshotFSCleanupScheduled = false

  def scheduleSnapshotCleanup(currentSerial: Long)(timestamp: FileTime) = {
    if (!snapshotFSCleanupScheduled) {
      system.scheduler.scheduleOnce(snapshotCleanInterval, new Runnable() {
        override def run() = {
          val command = CleanUpSnapshot(timestamp, currentSerial)
          dataCleaner ! command
          logger.debug(s"$command has been sent")
          snapshotFSCleanupScheduled = false
        }
      })
      snapshotFSCleanupScheduled = true
    }
  }

  def scheduleDeltaCleanups(deltasToDeleteFromFS: Seq[Long]) = {
    logger.info(s"Scheduling to remove deltas with serials: $deltasToDeleteFromFS")
    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod, new Runnable() {
      override def run() = {
        val command = CleanUpDeltas(sessionId, deltasToDeleteFromFS)
        dataCleaner ! command
        logger.debug(s"$command has been sent")
      }
    })
  }

  def deleteExtraDeltas(snapshotSize: Long): Seq[Long] = {
    val deltasToDelete = ListBuffer[Long]()
    while (deltasTotalSize > snapshotSize && deltas.nonEmpty) {
      val (serial, _, size, _) = deltas.dequeue()
      deltasTotalSize -= size
      deltasToDelete += serial
    }
    deltasToDelete
  }
}


class Cleaner(conf: AppConfig) extends Actor with Logging {

  private lazy val rrdpWriter = wire[RrdpRepositoryWriter]

  override def receive = {
    case CleanUpSnapshot(timestamp, serial) =>
      logger.info(s"Removing snapshots older than $timestamp and having serial number older than $serial")
      rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, timestamp, serial)
    case CleanUpDeltas(sessionId, serials) =>
      logger.info(s"Removing deltas with serials: $serials")
      rrdpWriter.deleteDeltas(conf.rrdpRepositoryPath, sessionId, serials)
  }

}

object Cleaner {
  def props(conf: AppConfig) = Props(new Cleaner(conf))
}
