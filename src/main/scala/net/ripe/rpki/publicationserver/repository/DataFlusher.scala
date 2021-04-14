package net.ripe.rpki.publicationserver.repository

import akka.actor.ActorSystem
import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.fs.{Rrdp, RrdpRepositoryWriter, RsyncRepositoryWriter}
import net.ripe.rpki.publicationserver.model.INITIAL_SERIAL
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import net.ripe.rpki.publicationserver.util.Time
import scalikejdbc.DBSession

import java.io.FileOutputStream
import java.net.URI
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Date, UUID}

class DataFlusher(conf: AppConfig)(implicit val system: ActorSystem)
  extends Hashing with Formatting with Logging {

  implicit val executionContext = system.dispatcher

  protected lazy val rrdpWriter = new RrdpRepositoryWriter
  protected lazy val rsyncWriter = new RsyncRepositoryWriter(conf)

  lazy val pgStore = PgStore.get(conf.pgConfig)

  // The latest serial for which version freeze was initiated by this server instance.
  private var latestFrozenSerial : Option[Long] = None

  // Write initial state of the database into RRDP and rsync repositories
  // Do not change anything in the DB here.
  def initFS() = {
    // do not do anything at all if neither `writeRsync` nor `writeRrdp` is set.
    if (conf.writeRsync || conf.writeRrdp) {
      logger.info("Initialising FS content")
      pgStore.inRepeatableReadTx { implicit session =>
        pgStore.lockVersions

        val ((sessionId, latestSerial, _), duration) = Time.timed(pgStore.freezeVersion)
        logger.info(s"Froze version $sessionId, $latestSerial, took ${duration}ms")

        if (conf.writeRsync) {
          initRsyncFS(sessionId, latestSerial)
        }

        if (conf.writeRrdp) {
          initRrdpFS(sessionId, latestSerial)
          initialRrdpRepositoryCleanup(UUID.fromString(sessionId))
        }

        latestFrozenSerial = Some(latestSerial)
      }
      logger.info("Done initialising FS content")
    }
  }

  // Write current state of the database into RRDP snapshot and delta and rsync repositories
  def updateFS() = {
    // do not do anything at all if neither `writeRsync` nor `writeRrdp` is set.
    if (conf.writeRsync || conf.writeRrdp) {
      pgStore.inRepeatableReadTx { implicit session =>
        pgStore.lockVersions

        if (pgStore.changesExist()) {
          val ((sessionId, serial, _), duration) = Time.timed(pgStore.freezeVersion)
          logger.info(s"Froze version $sessionId, $serial, took ${duration}ms")

          if (conf.writeRsync) {
            writeRsyncDelta(sessionId, serial)
          }

          if (conf.writeRrdp) {
            updateRrdpFS(sessionId, serial, latestFrozenSerial)
            val (_, cleanupDuration) = Time.timed {
              updateRrdpRepositoryCleanup()
            }
            logger.info(s"Cleanup $sessionId, $serial, took ${cleanupDuration}ms")
          }

          latestFrozenSerial = Some(serial)
        }
      }
    }
  }

  private def updateRrdpFS(sessionId: String, serial: Long, latestFrozenPreviously: Option[Long])(implicit session: DBSession) = {

    // Generate snapshot for the latest serial, we are only able to general the latest snapshot
    val (snapshotHash, snapshotSize) =
      withAtomicStream(snapshotPath(sessionId, serial), rrdpWriter.fileAttributes) {
        writeRrdpSnapshot(sessionId, serial, _)
      }
    pgStore.updateSnapshotInfo(sessionId, serial, snapshotHash, snapshotSize)

    // Convenience function
    def writeDelta(s: Long) = {
      val (deltaHash, deltaSize) =
        withAtomicStream(deltaPath(sessionId, s), rrdpWriter.fileAttributes) {
          writeRrdpDelta(sessionId, s, _)
        }
      pgStore.updateDeltaInfo(sessionId, s, deltaHash, deltaSize)
    }

    latestFrozenPreviously match {
      case None =>
        // Generate only the latest delta, it's the first time
        writeDelta(serial)

      case Some(previous) =>
        // Catch up on deltas, i.e. generate deltas from `latestFrozenPreviously` to `serial`.
        // This is to cover the case if version freeze was initiated by some other instance
        // and this instance is lagging behind.
        for (s <- (previous + 1) to serial) {
          writeDelta(s)
        }
    }

    val (_, d) = Time.timed {
      val deltas = pgStore.getReasonableDeltas(sessionId)
      rrdpWriter.writeNotification(conf.rrdpRepositoryPath) { os =>
        writeNotification(sessionId, serial, snapshotHash, deltas, new HashingSizedStream(os))
      }
    }
    logger.info(s"Generated notification $sessionId/$serial, took ${d}ms")

  }

  private def initRrdpFS(sessionId: String, latestSerial: Long)(implicit session: DBSession) = {
    val (snapshotHash, snapshotSize) =
      withAtomicStream(snapshotPath(sessionId, latestSerial), rrdpWriter.fileAttributes) {
        writeRrdpSnapshot(sessionId, latestSerial, _)
      }
    pgStore.updateSnapshotInfo(sessionId, latestSerial, snapshotHash, snapshotSize)

    if (latestSerial > INITIAL_SERIAL) {
      // When there are changes since the last freeze the latest delta might not yet exist, so ensure it gets created.
      // The `getReasonableDeltas` query below will then decide which deltas (if any) should be included in the
      // notification.xml file.
      val (latestDeltaHash, latestDeltaSize) =
        withAtomicStream(deltaPath(sessionId, latestSerial), rrdpWriter.fileAttributes) {
          writeRrdpDelta(sessionId, latestSerial, _)
        }

      pgStore.updateDeltaInfo(sessionId, latestSerial, latestDeltaHash, latestDeltaSize)
    }

    val deltas = pgStore.getReasonableDeltas(sessionId)
    for {
      (serial, _) <- deltas
      // Delta for the latest serial was already created above, so we can skip it here.
      if serial != latestSerial
    } {
      val (deltaHash, deltaSize) =
        withAtomicStream(deltaPath(sessionId, serial), rrdpWriter.fileAttributes) {
          writeRrdpDelta(sessionId, serial, _)
        }
      pgStore.updateDeltaInfo(sessionId, serial, deltaHash, deltaSize)
    }

    val (_, duration) = Time.timed {
      rrdpWriter.writeNotification(conf.rrdpRepositoryPath) { os =>
        writeNotification(sessionId, latestSerial, snapshotHash, deltas, new HashingSizedStream(os))
      }
    }
    logger.info(s"Generated notification $sessionId/$latestSerial, took ${duration}ms")
  }

  private def initRsyncFS(sessionId: String, latestSerial: Long)(implicit session: DBSession) = {
    writeRsyncSnapshot()
    writeRsyncDelta(sessionId, latestSerial)
  }

  // Write snapshot, i.e. the current state of the dataset into RRDP snapshot.xml file
  // and in rsync repository at the same time.
  def writeRrdpSnapshot(sessionId: String, serial: Long, snapshotOs: HashingSizedStream)(implicit session: DBSession) = {
    val (_, duration) = Time.timed {
      IOStream.string(s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", snapshotOs)
      pgStore.readState { (uri, _, bytes) =>
        writeObjectToSnapshotFile(uri, bytes, snapshotOs)
      }
      IOStream.string("</snapshot>\n", snapshotOs)
    }
    logger.info(s"Wrote RRDP snapshot for ${sessionId}/${serial}, took ${duration}ms.")
  }

  // Write snapshot objects to rsync repository.
  def writeRsyncSnapshot()(implicit session: DBSession) = {
    logger.info(s"Writing rsync snapshot.")
    val (_, duration) = Time.timed {
      val directoryMapping = rsyncWriter.startSnapshot
      pgStore.readState { (uri, _, bytes) =>
        rsyncWriter.writeFileInSnapshot(uri, bytes, directoryMapping)
      }
      rsyncWriter.promoteAllStagingToOnline(directoryMapping)
    }
    logger.info(s"Wrote rsync snapshot, took ${duration}ms.")
  }

  def writeRrdpDelta(sessionId: String, serial: Long, deltaOs: HashingSizedStream)(implicit session: DBSession) = {
    val (_, duration) = Time.timed {
      IOStream.string(s"""<delta version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", deltaOs)
      pgStore.readDelta(sessionId, serial) { (operation, uri, oldHash, bytes) =>
        writeLogEntryToDeltaFile(operation, uri, oldHash, bytes, deltaOs)
      }
      IOStream.string("</delta>\n", deltaOs)
    }
    logger.info(s"Wrote RRDP delta for ${sessionId}/${serial}, took ${duration}ms.")
  }

  def writeRsyncDelta(sessionId: String, serial: Long)(implicit session: DBSession) = {
    val (_, duration) = Time.timed {
      pgStore.readDelta(sessionId, serial) { (operation, uri, _, bytes) =>
        writeLogEntryToRsync(operation, uri, bytes)
      }
    }
    logger.info(s"Wrote rsync delta for ${sessionId}/${serial}, took ${duration}ms.")
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

  def snapshotPath(sessionId: String, serial: Long): Path =
    Files.createDirectories(Paths.get(conf.rrdpRepositoryPath, sessionId, String.valueOf(serial))).resolve(Rrdp.snapshotFilename)

  def deltaPath(sessionId: String, serial: Long): Path =
    Files.createDirectories(Paths.get(conf.rrdpRepositoryPath, sessionId, String.valueOf(serial))).resolve(Rrdp.deltaFilename)

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

  def withAtomicStream(targetFile: Path, attrs: FileAttributes)(f : HashingSizedStream => Unit) = {
    val tmpFile = Files.createTempFile(targetFile.getParent, "", ".xml", attrs)
    val tmpStream = new HashingSizedStream(new FileOutputStream(tmpFile.toFile))
    try {
      f(tmpStream)
      tmpStream.flush()
      Files.move(tmpFile, targetFile.toAbsolutePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      tmpStream.summary
    } finally {
      tmpStream.close()
      Files.deleteIfExists(tmpFile)
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
    // First remove the ones that are more than `oldEnough`
    logger.info(s"Removing all the sessions in RRDP repository except for $sessionId")
    rrdpWriter.cleanSessionsOlderThanExceptForOne(conf.rrdpRepositoryPath, oldEnough, sessionId)

    // Wait for the time T and delete those which are older than `now`
    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod) {
      logger.info(s"Removing all the older sessions in RRDP repository except for $sessionId")
      rrdpWriter.cleanSessionsOlderThanExceptForOne(conf.rrdpRepositoryPath, FileTime.from(now), sessionId)
      ()
    }
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
