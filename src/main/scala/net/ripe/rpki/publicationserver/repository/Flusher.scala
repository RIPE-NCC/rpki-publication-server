package net.ripe.rpki.publicationserver.repository

import java.io.FileOutputStream
import java.net.URI
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.util.Date

import akka.actor.{Actor, Props}
import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.fs.{Rrdp, RrdpRepositoryWriter, RsyncRepositoryWriter}
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import scalikejdbc.DBSession

class DataFlusher(conf: AppConfig) extends Hashing with Formatting with Logging {

  protected lazy val rrdpWriter = new RrdpRepositoryWriter
  protected lazy val rsyncWriter = new RsyncRepositoryWriter(conf)

  lazy val pgStore = PgStore.get(conf.pgConfig)

  // Write initial state of the database into RRDP and rsync repositories
  // Do not change anything in the DB here.
  def initFS() = withDbVersion { (sessionId, serial, session) =>
    implicit val s1 = session
    val (snapshotHash, snapshotSize) = writeSnapshot(sessionId, serial, true)
    pgStore.updateSnapshotInfo(sessionId, serial, snapshotHash, snapshotSize)

    val deltas = pgStore.getReasonableDeltas(sessionId)
    deltas.foreach { case (serial, _) =>
      writeDelta(sessionId, serial, false)
    }

    val notification = Notification.create(conf, sessionId, serial, snapshotHash, deltas)
    rrdpWriter.writeNotification(conf.rrdpRepositoryPath, notification)
  }

  // Write current state of the database into RRDP snapshot and delta and rsync repositories
  def updateFS() = withDbVersion { (sessionId, serial, session) =>
    implicit val session1 = session

    // create a new entry in the versions table to mark the new serial
    pgStore.freezeVersion

    val (snapshotHash, snapshotSize) = writeSnapshot(sessionId, serial, false)
    val (deltaHash, deltaSize) = writeDelta(sessionId, serial, true)

    pgStore.updateDeltaInfo(sessionId, serial, deltaHash, deltaSize)
    pgStore.updateSnapshotInfo(sessionId, serial, snapshotHash, snapshotSize)

    val deltas = pgStore.getReasonableDeltas(sessionId)
    val notification = Notification.create(conf, sessionId, serial, snapshotHash, deltas)
    rrdpWriter.writeNotification(conf.rrdpRepositoryPath, notification)
  }

  def writeSnapshot(sessionId: String, serial: Long, writeRsync: Boolean)(implicit session: DBSession) = {
    withOS(snapshotStream(sessionId, serial)) { snapshotOs =>
      IOStream.string(s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", snapshotOs)
      pgStore.readState { (uri, _, bytes) =>
        writeObjectToSnapshotFile(uri, bytes, snapshotOs)

        // TODO This is not enough, there must be logic similar to
        // RsyncRepositoryWriter.writeSnapshot
        if (writeRsync) {
          rsyncWriter.writeFile(uri, bytes)
        }
      }
      IOStream.string("</snapshot>\n", snapshotOs)
    }
  }

  def writeDelta(sessionId: String, serial: Long, writeRsync: Boolean)(implicit session: DBSession) = {
    withOS(deltaStream(sessionId, serial)) { deltaOs =>
      IOStream.string(s"""<delta version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", deltaOs)
      pgStore.readLatestDelta { (operation, uri, oldHash, bytes) =>
        writeLogEntryToDeltaFile(operation, uri, oldHash, bytes, deltaOs)
        if (writeRsync) {
          writeLogEntryToRsync(operation, uri, bytes)
        }
      }
      IOStream.string("</delta>\n", deltaOs)
    }
  }

  def withDbVersion[T](f: (String, Long, DBSession) => T) = {
    pgStore.inTx { implicit session =>
      pgStore.lockVersions
      pgStore.getCurrentSessionInfo match {
        case None =>
          throw new Exception("Database is not initialised, versions table is empty")

        case Some((sessionId, serial)) =>
          f(sessionId, serial, session)
      }
    }
  }

  def deltaStream(sessionId: String, serial: Long): HashingSizedStream =
    new HashingSizedStream(rrdpFileStream(sessionId, serial, Rrdp.deltaFilename))

  def snapshotStream(sessionId: String, serial: Long): HashingSizedStream =
    new HashingSizedStream(rrdpFileStream(sessionId, serial, Rrdp.snapshotFilename))

  private def rrdpFileStream(sessionId: String, serial: Long, localName: String) = {
    val stateDir = Files.createDirectories(Paths.get(conf.rrdpRepositoryPath, sessionId, String.valueOf(serial)))
    new FileOutputStream(stateDir.resolve(localName).toFile)
  }

  protected def writeLogEntryToRsync(operation: String, uri: URI, bytes: Option[Bytes]) =
    (operation, bytes) match {
      case ("INS", Some(b)) => rsyncWriter.writeFile(uri, b)
      case ("UPD", Some(b)) => rsyncWriter.writeFile(uri, b)
      case ("DEL", _)       => rsyncWriter.removeFile(uri)
    }

  private def writeObjectToSnapshotFile(uri: URI, bytes: Bytes, stream: HashingSizedStream): Unit =
    writePublish(uri, bytes, stream)

  def writeLogEntryToDeltaFile(operation: String, uri: URI, oldHash: Option[Hash], bytes: Option[Bytes], stream: HashingSizedStream) = {
    (operation, oldHash, bytes) match {
      case ("INS", None, Some(bytes)) =>
        writePublish(uri, bytes, stream)
      case ("UPD", Some(Hash(hash)), Some(bytes)) =>
        IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}" hash="$hash">""", stream)
        IOStream.string(Bytes.toBase64(bytes).value, stream)
        IOStream.string("</publish>\n", stream)
      case ("DEL", Some(Hash(hash)), None) =>
        IOStream.string(s"""<withdraw uri="${attr(uri.toASCIIString)}" hash="$hash"/>\n""", stream)
      case anythingElse =>
        logger.error(s"Log contains invalid row ${anythingElse}")
    }
  }

  private def writePublish(uri: URI, bytes: Bytes, stream: HashingSizedStream) = {
    IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}">""", stream)
    IOStream.string(Bytes.toBase64(bytes).value, stream)
    IOStream.string("</publish>\n", stream)
  }

  def withOS(createOS: => HashingSizedStream)(f : HashingSizedStream => Unit) = {
    val os = createOS
    try {
      f(os)
      os.info
    } finally {
      os.close()
    }
  }

//  def scheduleRrdpRepositoryCleanup() = {
//    logger.debug("Clean up " + sessionId)
//    val oldEnough = FileTime.from(Instant.now().minus(conf.unpublishedFileRetainPeriod.toSeconds, ChronoUnit.SECONDS))
//    rrdpCleaner ! CleanUpRepoOldOnesNow(oldEnough, sessionId)
//    scheduleCleanup(CleanUpRepo(sessionId))
//  }

//  def scheduleSnapshotCleanup(currentSerial: Long)(timestamp: FileTime) = scheduleCleanup(CleanUpSnapshot(timestamp, currentSerial))
//
//  def scheduleDeltaCleanups(deltasToDeleteFromFS: Seq[Long]) = scheduleCleanup(CleanUpDeltas(sessionId, deltasToDeleteFromFS))

//  def scheduleCleanup(message: => Any) =
//    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod, rrdpCleaner, message)


//  def initFS(state: ObjectStore.State) = {
//    val serverState = ServerState(sessionId, serial)
//    val snapshotPdus = state.map { e =>
//      val (uri, (bytes, _, _)) = e
//      (bytes, uri)
//    }.toSeq
//
//    val snapshot = Snapshot(serverState, snapshotPdus)
//    val notification = Notification.create(conf)(snapshot, serverState, Seq())
//
//    logger.info("Writing initial RRDP state to " + conf.rrdpRepositoryPath)
//    rrdpWriter.writeNewState(conf.rrdpRepositoryPath, serverState, notification, snapshot)
//      .recover {
//        case e: Exception =>
//          logger.error(s"Could not write snapshot to rrdp repo: ", e)
//          throwFatalException
//      }
//  }

//  def updateFS(messages: Seq[QueryMessage], state: ObjectStore.State): Any = {
//    val pdus = messages.flatMap(_.pdus)
//    val delta = Delta(sessionId, serial, pdus)
//    deltas.enqueue((serial, delta.contentHash, delta.binarySize, Instant.now()))
//    deltasTotalSize += delta.binarySize
//
//    val serverState = ServerState(sessionId, serial)
//    val snapshotPdus = state.map { case (uri, (bytes, _, _)) => (bytes, uri) }.toSeq
//
//    val snapshot = Snapshot(serverState, snapshotPdus)
//    val deltasToDeleteFromFS = deleteExtraDeltas(snapshot.binarySize)
//    val notification = Notification.create(conf)(snapshot, serverState, deltas.map(e => (e._1, e._2)))
//
//    Try {
//      logger.info(s"Writing delta $serial to RRDP filesystem")
//      rrdpWriter.writeDelta(conf.rrdpRepositoryPath, delta)
//    } flatMap { _ =>
//      logger.info(s"Writing snapshot $serial to RRDP filesystem")
//      rrdpWriter.writeNewState(conf.rrdpRepositoryPath, serverState, notification, snapshot)
//    } match {
//      case Success(timestampOption) =>
//        timestampOption.foreach(scheduleSnapshotCleanup(serial))
//        if (deltasToDeleteFromFS.nonEmpty) {
//          scheduleDeltaCleanups(deltasToDeleteFromFS)
//        }
//      case Failure(e) =>
//        logger.error("Could not update RRDP files: ", e)
//    }
//  }

  def afterRetainPeriod = new Date(System.currentTimeMillis() + conf.unpublishedFileRetainPeriod.toMillis)

}


class RrdpCleaner(conf: AppConfig) extends Actor with Logging {

  private val rrdpWriter = new RrdpRepositoryWriter

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
