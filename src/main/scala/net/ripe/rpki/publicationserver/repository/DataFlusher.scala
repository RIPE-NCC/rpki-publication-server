package net.ripe.rpki.publicationserver.repository

import java.io.FileOutputStream
import java.net.URI
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Date, UUID}

import akka.actor.ActorSystem
import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.fs.{Rrdp, RrdpRepositoryWriter, RsyncRepositoryWriter}
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import net.ripe.rpki.publicationserver.util.Time
import scalikejdbc.DBSession

class DataFlusher(conf: AppConfig)(implicit val system: ActorSystem)
  extends Hashing with Formatting with Logging {

  implicit val executionContext = system.dispatcher

  protected lazy val rrdpWriter = new RrdpRepositoryWriter
  protected lazy val rsyncWriter = new RsyncRepositoryWriter(conf)

  lazy val pgStore = PgStore.get(conf.pgConfig)

  // Write initial state of the database into RRDP and rsync repositories
  // Do not change anything in the DB here.
  def initFS() = pgStore.inRepeatableReadTx { implicit session =>
    pgStore.lockVersions

    val thereAreChangesSinceTheLastFreeze = pgStore.changesExist()

    val (sessionId, latestSerial, _) = pgStore.freezeVersion
    val (snapshotHash, snapshotSize) =
      withOS(snapshotStream(sessionId, latestSerial)) { snapshotOs =>
        writeSnapshot(sessionId, latestSerial, true, snapshotOs)
      }
    pgStore.updateSnapshotInfo(sessionId, latestSerial, snapshotHash, snapshotSize)

    if (thereAreChangesSinceTheLastFreeze) {
      val (latestDeltaHash, latestDeltaSize) =
        withOS(deltaStream(sessionId, latestSerial)) { deltaOs =>
          writeDelta(sessionId, latestSerial, false, deltaOs)
        }
      pgStore.updateDeltaInfo(sessionId, latestSerial, latestDeltaHash, latestDeltaSize)
    }

    // After version free this list of deltas contains the latest one as well
    // so it is correct to use it for the notifications.xml
    val deltas = pgStore.getReasonableDeltas(sessionId)

    deltas.filter(_._1 != latestSerial).foreach { case (serial, _) =>
      withOS(deltaStream(sessionId, serial)) { deltaOs =>
        writeDelta(sessionId, serial, false, deltaOs)
      }
    }

    rrdpWriter.writeNotification(conf.rrdpRepositoryPath) { os =>
      writeNotification(sessionId, latestSerial, snapshotHash, deltas, new HashingSizedStream(os))
    }

    initialRrdpRepositoryCleanup(UUID.fromString(sessionId))
  }

  // Write current state of the database into RRDP snapshot and delta and rsync repositories
  def updateFS() = pgStore.inRepeatableReadTx { implicit session =>
    pgStore.lockVersions

    if (pgStore.changesExist(conf.snapshotSyncDelay.toSeconds)) {
      val ((sessionId, serial, _), duration) = Time.timed(pgStore.freezeVersion)
      logger.info(s"Froze version $sessionId, $serial, took ${duration}ms")

      val ((snapshotHash, snapshotSize), snapshotDuration) = Time.timed {
        withOS(snapshotStream(sessionId, serial)) { snapshotOs =>
          writeSnapshot(sessionId, serial, false, snapshotOs)
        }
      }
      logger.info(s"Generated snapshot $sessionId, $serial, took ${snapshotDuration}ms")

      val ((deltaHash, deltaSize), deltaDuration) = Time.timed {
        withOS(deltaStream(sessionId, serial)) { deltaOs =>
          writeDelta(sessionId, serial, true, deltaOs)
        }
      }
      logger.info(s"Generated delta $sessionId, $serial, took ${deltaDuration}ms")

      pgStore.updateDeltaInfo(sessionId, serial, deltaHash, deltaSize)
      pgStore.updateSnapshotInfo(sessionId, serial, snapshotHash, snapshotSize)

      val (_, d)  = Time.timed {
        val deltas = pgStore.getReasonableDeltas(sessionId)
        rrdpWriter.writeNotification(conf.rrdpRepositoryPath) { os =>
          writeNotification(sessionId, serial, snapshotHash, deltas, new HashingSizedStream(os))
        }
      }
      logger.info(s"Generated notification $sessionId, $serial, took ${d}ms")

      val (_, cleanupDuration)  = Time.timed {
        updateRrdpRepositoryCleanup()
      }
      logger.info(s"Cleanup $sessionId, $serial, took ${cleanupDuration}ms")
    }
  }

  // Write snapshot, i.e. the current state of the dataset into RRDP snapshot.xml file
  // and in rsync repository at the same time.
  def writeSnapshot(sessionId: String, serial: Long, writeRsync: Boolean, snapshotOs: HashingSizedStream)(implicit session: DBSession) = {
    IOStream.string(s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", snapshotOs)
    if (writeRsync) {
      val directoryMapping = rsyncWriter.startSnapshot
      pgStore.readState { (uri, _, bytes) =>
        writeObjectToSnapshotFile(uri, bytes, snapshotOs)
        rsyncWriter.writeFileInSnapshot(uri, bytes, directoryMapping)
      }
      rsyncWriter.promoteAllStagingToOnline(directoryMapping)
    } else {
      pgStore.readState { (uri, _, bytes) =>
        writeObjectToSnapshotFile(uri, bytes, snapshotOs)
      }
    }
    IOStream.string("</snapshot>\n", snapshotOs)
  }

  def writeDelta(sessionId: String, serial: Long, writeRsync: Boolean, deltaOs: HashingSizedStream)(implicit session: DBSession) = {
    IOStream.string(s"""<delta version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", deltaOs)
    pgStore.readDelta(sessionId, serial) { (operation, uri, oldHash, bytes) =>
      writeLogEntryToDeltaFile(operation, uri, oldHash, bytes, deltaOs)
      if (writeRsync) {
        writeLogEntryToRsync(operation, uri, bytes)
      }
    }
    IOStream.string("</delta>\n", deltaOs)
  }

  def writeNotification(sessionId: String, serial: Long, snapshotHash: Hash, deltas: Seq[(Long, Hash)], stream: HashingSizedStream) = {
    IOStream.string(s"""<notification version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    IOStream.string(s"""  <snapshot uri="${conf.snapshotUrl(sessionId, serial)}" hash="${snapshotHash.hash}"/>\n""", stream)
    deltas.foreach { case (deltaSerial, deltaHash) =>
      val deltaUrl = conf.deltaUrl(sessionId, deltaSerial)
      IOStream.string(s"""  <delta serial="$deltaSerial" uri="${deltaUrl}" hash="${deltaHash.hash}"/>\n""", stream)
    }
    IOStream.string("</notification>", stream)
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
      case anythingElse =>
        logger.error(s"Log contains invalid row $anythingElse")
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
        logger.error(s"Log contains invalid row $anythingElse")
    }
  }

  private def writePublish(uri: URI, bytes: Bytes, stream: HashingSizedStream) = {
    IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}">""", stream)
    IOStream.string(Bytes.toBase64(bytes).value, stream)
    IOStream.string("</publish>\n", stream)
  }

  def withOS(createStream: => HashingSizedStream)(f : HashingSizedStream => Unit) = {
    val stream = createStream
    try {
      f(stream)
      stream.summary
    } finally {
      stream.close()
    }
  }

  def afterRetainPeriod = new Date(System.currentTimeMillis() + conf.unpublishedFileRetainPeriod.toMillis)

  def initialRrdpRepositoryCleanup(sessionId: UUID)(implicit session: DBSession) = {

    val now = Instant.now()
    val oldEnough = FileTime.from(now.minus(conf.unpublishedFileRetainPeriod.toMillis, ChronoUnit.MILLIS))

    // Delete files related to the current sessions
    updateRrdpRepositoryCleanup()

    // Cleanup files that are left from some previously existing sessions
    //
    // First remove the ones that are more than time T old
    logger.info(s"Removing all the sessions in RRDP repository except for $sessionId")
    rrdpWriter.cleanRepositoryExceptOneSessionOlderThan(conf.rrdpRepositoryPath, oldEnough, sessionId)

    // Wait for the time T and delete those which are older than `now`
    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod)({
      logger.info(s"Removing all the older sessions in RRDP repository except for $sessionId")
      rrdpWriter.cleanRepositoryExceptOneSessionOlderThan(conf.rrdpRepositoryPath, FileTime.from(now), sessionId)
      ()
    })
  }


  def updateRrdpRepositoryCleanup()(implicit session: DBSession) = {

    val oldEnough = FileTime.from(Instant.now()
      .minus(conf.unpublishedFileRetainPeriod.toMillis, ChronoUnit.MILLIS))

    // Delete version that are removed from the database
    pgStore.deleteOldVersions.groupBy(_._1).foreach {
      case (sessionId, ss) =>
        val serials = ss.map(_._2).toSet
        system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod)({
          rrdpWriter.deleteDeltas(conf.rrdpRepositoryPath, UUID.fromString(sessionId), serials)
          val oldestSerial = serials.min
          logger.info(s"Removing snapshots from the session $sessionId older than $oldEnough and having serial number older than $oldestSerial")
          rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, oldEnough, oldestSerial + 1)
        })
    }
  }

}
