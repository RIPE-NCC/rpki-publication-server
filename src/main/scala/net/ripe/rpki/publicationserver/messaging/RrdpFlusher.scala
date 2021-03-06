package net.ripe.rpki.publicationserver.messaging

import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.{Date, UUID}

import akka.actor.{Actor, Props}
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

object RrdpFlusher {
  def props(conf: AppConfig) = Props(new RrdpFlusher(conf))
}

class RrdpFlusher(conf: AppConfig) extends Actor with Logging {

  import context._

  protected lazy val rrdpWriter = new RrdpRepositoryWriter(conf)

  private val deltas = mutable.Queue[(Long, Hash, Int, Instant)]()
  private var deltasTotalSize = 0L

  val sessionId = UUID.randomUUID()

  private var serial = 1L

  val rrdpCleaner = actorOf(RrdpCleaner.props(conf))

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
      logger.debug(s"Initializing repo, with state: $state") 
      scheduleRrdpRepositoryCleanup()
      initFS(state)
      serial += 1
  }


  def scheduleRrdpRepositoryCleanup() = {
    logger.debug("Clean up " + sessionId)
    val oldEnough = FileTime.from(Instant.now().minus(conf.unpublishedFileRetainPeriod.toSeconds, ChronoUnit.SECONDS))
    rrdpCleaner ! CleanUpRepoOldOnesNow(oldEnough, sessionId)
    scheduleCleanup(CleanUpRepo(sessionId))
  }

  def scheduleSnapshotCleanup(currentSerial: Long)(timestamp: FileTime) = scheduleCleanup(CleanUpSnapshot(timestamp, currentSerial))

  def scheduleDeltaCleanups(deltasToDeleteFromFS: Seq[Long]) = scheduleCleanup(CleanUpDeltas(sessionId, deltasToDeleteFromFS))

  def scheduleCleanup(message: => Any) =
    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod, rrdpCleaner, message)


  def initFS(state: ObjectStore.State) = {
    val serverState = ServerState(sessionId, serial)
    val snapshotPdus = state.map { e =>
      val (uri, (bytes, _, _)) = e
      (bytes, uri)
    }.toSeq

    val snapshot = Snapshot(serverState, snapshotPdus)
    val notification = Notification.create(conf)(snapshot, serverState, Seq())

    logger.info("Writing initial RRDP state to " + conf.rrdpRepositoryPath)
    rrdpWriter.writeNewState(conf.rrdpRepositoryPath, serverState, notification, snapshot)
      .recover {
        case e: Exception =>
          logger.error(s"Could not write snapshot to rrdp repo: ", e)
          throwFatalException
      }
  }

  def updateFS(messages: Seq[QueryMessage], state: ObjectStore.State): Any = {
    val pdus = messages.flatMap(_.pdus)
    val delta = Delta(sessionId, serial, pdus)
    deltas.enqueue((serial, delta.contentHash, delta.binarySize, Instant.now()))
    deltasTotalSize += delta.binarySize

    val serverState = ServerState(sessionId, serial)
    val snapshotPdus = state.map { case (uri, (bytes, _, _)) => (bytes, uri) }.toSeq

    val snapshot = Snapshot(serverState, snapshotPdus)
    val deltasToDeleteFromFS = deleteExtraDeltas(snapshot.binarySize)
    val notification = Notification.create(conf)(snapshot, serverState, deltas.map(e => (e._1, e._2)))

    Try {
      logger.info(s"Writing delta $serial to RRDP filesystem")
      rrdpWriter.writeDelta(conf.rrdpRepositoryPath, delta)
    } flatMap { _ =>
      logger.info(s"Writing snapshot $serial to RRDP filesystem")
      rrdpWriter.writeNewStateWithNotificationDelay(conf.rrdpRepositoryPath, serverState, notification, snapshot)
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

  def afterRetainPeriod = new Date(System.currentTimeMillis() + conf.unpublishedFileRetainPeriod.toMillis)

  def deleteExtraDeltas(snapshotSize: Long): Seq[Long] = {
    val deltasToDelete = ListBuffer[Long]()
    while (deltasTotalSize > snapshotSize && deltas.nonEmpty) {
      val (serial, _, size, _) = deltas.dequeue()
      deltasTotalSize -= size
      deltasToDelete += serial
    }
    deltasToDelete
  }

  def currentSerial: Long = serial
}


class RrdpCleaner(conf: AppConfig) extends Actor with Logging {

  private val rrdpWriter = new RrdpRepositoryWriter(conf)

  override def receive = {
    case CleanUpSnapshot(timestamp, serial) =>
      logger.info(s"Removing snapshots older than $timestamp and having serial number older than $serial")
      rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, timestamp, serial)
    case CleanUpDeltas(sessionId, serials) =>
      logger.info(s"Removing deltas with serials: $serials")
      rrdpWriter.deleteDeltas(conf.rrdpRepositoryPath, sessionId, serials)
    case CleanUpRepo(sessionId) =>
      logger.info(s"Removing all the sessions in RRDP repository except for $sessionId")
      rrdpWriter.cleanRepositoryExceptOneSessionOlderThan(conf.rrdpRepositoryPath, FileTime.from(Instant.now()), sessionId)
    case CleanUpRepoOldOnesNow(timestamp, sessionId) =>
      logger.info(s"Removing all the older sessions in RRDP repository except for $sessionId")
      rrdpWriter.cleanRepositoryExceptOneSessionOlderThan(conf.rrdpRepositoryPath, timestamp, sessionId)
  }

}

object RrdpCleaner {
  def props(conf: AppConfig) = Props(new RrdpCleaner(conf))
}
