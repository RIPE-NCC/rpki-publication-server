package net.ripe.rpki.publicationserver.repository

import akka.actor.ActorSystem
import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.fs.{Rrdp, RrdpRepositoryWriter, RsyncRepositoryWriter}
import net.ripe.rpki.publicationserver.store.postgresql.{DeltaInfo, PgStore, SnapshotInfo, VersionInfo}
import net.ripe.rpki.publicationserver.util.Time
import scalikejdbc.DBSession

import java.io.{FileOutputStream, OutputStream}
import java.net.URI
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Date, UUID}
import scala.util.control.NonFatal


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
  def initFS() = try {
    // do not do anything at all if neither `writeRsync` nor `writeRrdp` is set.
    if (conf.writeRsync || conf.writeRrdp) {
      logger.info("Initialising FS content")
      pgStore.inRepeatableReadTx { implicit session =>
        pgStore.lockVersions

        val (version, duration) = Time.timed(pgStore.freezeVersion)
        logger.info(s"Froze version ${version.sessionId}, ${version.serial}, took ${duration}ms")

        if (conf.writeRsync) {
          initRsyncFS()
        }

        if (conf.writeRrdp) {
          initRrdpFS(version)
          initialRrdpRepositoryCleanup(UUID.fromString(version.sessionId))
        }

        latestFrozenSerial = Some(version.serial)
      }
      logger.info("Done initialising FS content")
    }
  } catch {
    case NonFatal(e) =>
      logger.error("Failed to initialise FS content", e)
      throw e
  }

  // Write current state of the database into RRDP snapshot and delta and rsync repositories
  def updateFS() = try {
    // do not do anything at all if neither `writeRsync` nor `writeRrdp` is set.
    if (conf.writeRsync || conf.writeRrdp) {
      logger.info("Updating FS content")
      pgStore.inRepeatableReadTx { implicit session =>
        pgStore.lockVersions

        if (pgStore.changesExist()) {
          val (version, duration) = Time.timed(pgStore.freezeVersion)
          logger.info(s"Froze version ${version.sessionId}, ${version.serial}, took ${duration}ms")

          if (conf.writeRsync) {
            writeRsyncDelta(version.sessionId, version.serial)
          }

          if (conf.writeRrdp) {
            updateRrdpFS(version, latestFrozenSerial)
            val (_, cleanupDuration) = Time.timed {
              updateRrdpRepositoryCleanup()
            }
            logger.info(s"Cleanup ${version.sessionId}, ${version.serial}, took ${cleanupDuration}ms")
          }

          latestFrozenSerial = Some(version.serial)
          logger.info("Done updating FS content")
        } else {
          logger.info("No changes exist, nothing to update")
        }
      }
    }
  } catch {
    case NonFatal(e) =>
      logger.error("failed to update FS", e)
      throw e
  }

  private def updateSnapshot(version: VersionInfo)(implicit session: DBSession) = {
    import version._
    val snapshotPath = rrdpFilePath(sessionId, serial)

    val (snapshotFileName, snapshotHash, snapshotSize) = withAtomicStream(snapshotPath, fileNameSecret, snapshotFileNameFromHmac, rrdpWriter.fileAttributes) {
      writeRrdpSnapshot(sessionId, serial, _)
    }

    val info = SnapshotInfo(version, snapshotFileName, snapshotHash, snapshotSize)
    pgStore.updateSnapshotInfo(info)
    info
  }


  private def updateDelta(version: VersionInfo)(implicit session: DBSession) = {
    import version._
    val deltaPath = rrdpFilePath(sessionId, serial)

    val (deltaFileName, deltaHash, deltaSize) = withAtomicStream(deltaPath, fileNameSecret, deltaFileNameFromHmac, rrdpWriter.fileAttributes) {
      writeRrdpDelta(sessionId, serial, _)
    }

    val deltaInfo = DeltaInfo(version, deltaFileName, deltaHash, deltaSize)
    pgStore.updateDeltaInfo(deltaInfo)
    deltaInfo
  }

  private def updateRrdpFS(version: VersionInfo, latestFrozenPreviously: Option[Long])(implicit session: DBSession) = {
    import version._

    // Generate snapshot for the latest serial, we are only able to generate the latest snapshot
    val snapshotInfo = updateSnapshot(version)
    if (!version.isInitialSerial) {
      updateDelta(version)
    }

    latestFrozenPreviously match {
      case None =>
        // Generate only the latest delta, it's the first time

      case Some(previous) =>
        // Catch up on deltas, i.e. generate deltas from `latestFrozenPreviously` to `serial`.
        // This is to cover the case if version freeze was initiated by some other instance
        // and this instance is lagging behind.
        val deltas = pgStore.getReasonableDeltas(version.sessionId)
        for (delta <- deltas if delta.serial > previous && delta.serial < version.serial) {
          updateDelta(delta.version)
        }
    }

    val (_, d) = Time.timed {
      val deltas = pgStore.getReasonableDeltas(sessionId)
      rrdpWriter.writeNotification(conf.rrdpRepositoryPath) { os =>
        writeNotification(snapshotInfo, deltas, os)
      }
    }
    logger.info(s"Generated notification $sessionId/$serial, took ${d}ms")

  }

  private def initRrdpFS(latestVersion: VersionInfo)(implicit session: DBSession) = {
    val snapshotInfo = updateSnapshot(latestVersion)

    if (!latestVersion.isInitialSerial) {
      // When there are changes since the last freeze the latest delta might not yet exist, so ensure it gets created.
      // The `getReasonableDeltas` query below will then decide which deltas (if any) should be included in the
      // notification.xml file.
      updateDelta(latestVersion)
    }

    val deltas = for {
      delta <- pgStore.getReasonableDeltas(latestVersion.sessionId)
      if delta.serial < latestVersion.serial
    } yield {
      updateDelta(delta.version)
    }

    val (_, duration) = Time.timed {
      rrdpWriter.writeNotification(conf.rrdpRepositoryPath) { os =>
        writeNotification(snapshotInfo, deltas, os)
      }
    }
    logger.info(s"Generated notification ${latestVersion.sessionId}/${latestVersion.serial}, took ${duration}ms")
  }

  private def initRsyncFS()(implicit session: DBSession) = {
    writeRsyncSnapshot()
  }

  // Write snapshot, i.e. the current state of the dataset into RRDP snapshot.xml file
  // and in rsync repository at the same time.
  def writeRrdpSnapshot(sessionId: String, serial: Long, snapshotOs: OutputStream)(implicit session: DBSession) = {
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

  def writeRrdpDelta(sessionId: String, serial: Long, deltaOs: OutputStream)(implicit session: DBSession) = {
    val (_, duration) = Time.timed {
      IOStream.string(s"""<delta version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", deltaOs)
      pgStore.readDelta(sessionId, serial) { (uri, oldHash, bytes) =>
        writeLogEntryToDeltaFile(uri, oldHash, bytes, deltaOs)
      }
      IOStream.string("</delta>\n", deltaOs)
    }
    logger.info(s"Wrote RRDP delta for ${sessionId}/${serial}, took ${duration}ms.")
  }

  def writeRsyncDelta(sessionId: String, serial: Long)(implicit session: DBSession) = {
    val (_, duration) = Time.timed {
      pgStore.readDelta(sessionId, serial) { (uri, oldHash, bytes) =>
        writeLogEntryToRsync(uri, oldHash, bytes)
      }
    }
    logger.info(s"Wrote rsync delta for ${sessionId}/${serial}, took ${duration}ms.")
  }


  def writeNotification(snapshotInfo: SnapshotInfo, deltas: Seq[DeltaInfo], stream: OutputStream) = {
    require(deltas.forall(_.sessionId == snapshotInfo.sessionId), s"sessionId mismatch between snapshot and delta: $snapshotInfo <-> $deltas")
    if (deltas.nonEmpty) {
      require(deltas.head.serial == snapshotInfo.serial, s"most recent delta serial must match snapshot serial: $snapshotInfo <-> $deltas")
      require(deltas.zip(deltas.tail).forall { case (a, b) => a.serial == b.serial + 1 }, s"delta serials must be consecutive: $deltas")
    }

    IOStream.string(s"""<notification version="1" session_id="${snapshotInfo.sessionId}" serial="${snapshotInfo.serial}" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    val snapshotUrl = conf.snapshotUrl(snapshotInfo)
    IOStream.string(s"""  <snapshot uri="$snapshotUrl" hash="${snapshotInfo.hash.toHex}"/>\n""", stream)
    deltas.foreach { delta =>
      val deltaUrl = conf.deltaUrl(delta)
      IOStream.string(s"""  <delta serial="${delta.serial}" uri="$deltaUrl" hash="${delta.hash.toHex}"/>\n""", stream)
    }
    IOStream.string("</notification>", stream)
  }

  def rrdpFilePath(sessionId: String, serial: Long): Path = Files.createDirectories(commonSubPath(sessionId, serial))

  def deltaFileNameFromHmac(hmac: Bytes): String = Rrdp.deltaFileNameWithExtra(hmac.toHex)

  def snapshotFileNameFromHmac(hmac: Bytes): String = Rrdp.snapshotFileNameWithExtra(hmac.toHex)

  private def commonSubPath(sessionId: String, serial: Long) = {
    Paths.get(conf.rrdpRepositoryPath, sessionId, String.valueOf(serial))
  }

  protected def writeLogEntryToRsync(uri: URI, oldHash: Option[Hash], bytes: Option[Bytes]) =
    (oldHash, bytes) match {
      case (_, Some(b)) => rsyncWriter.writeFile(uri, b)
      case (Some(_), None) => rsyncWriter.removeFile(uri)
      case anythingElse =>
        logger.error(s"Log contains invalid row $anythingElse")
    }

  private def writeObjectToSnapshotFile(uri: URI, bytes: Bytes, stream: OutputStream): Unit =
    writePublish(uri, bytes, stream)

  def writeLogEntryToDeltaFile(uri: URI, oldHash: Option[Hash], bytes: Option[Bytes], stream: OutputStream) = {
    (oldHash, bytes) match {
      case (None, Some(bytes)) =>
        writePublish(uri, bytes, stream)
      case (Some(hash), Some(bytes)) =>
        IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}" hash="${hash.toHex}">""", stream)
        IOStream.string(Bytes.toBase64(bytes).value, stream)
        IOStream.string("</publish>\n", stream)
      case (Some(hash), None) =>
        IOStream.string(s"""<withdraw uri="${attr(uri.toASCIIString)}" hash="${hash.toHex}"/>\n""", stream)
      case anythingElse =>
        logger.error(s"Log contains invalid row $anythingElse")
    }
  }

  private def writePublish(uri: URI, bytes: Bytes, stream: OutputStream) = {
    IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}">""", stream)
    IOStream.string(Bytes.toBase64(bytes).value, stream)
    IOStream.string("</publish>\n", stream)
  }

  def withAtomicStream(targetDirectory: Path, secret: Bytes, filenameFromMac: Bytes => String, attrs: FileAttributes)(f : OutputStream => Unit) = {
    val tmpFile = Files.createTempFile(targetDirectory, "", ".xml", attrs)
    val tmpStream = new HashingSizedStream(secret, new FileOutputStream(tmpFile.toFile))
    try {
      f(tmpStream)
      tmpStream.flush()
      val (hash, mac, size) = tmpStream.summary
      val filename = filenameFromMac(mac)
      Files.move(tmpFile, targetDirectory.resolve(filename).toAbsolutePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      (filename, hash, size)
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
    rrdpWriter.deleteSessionsOlderThanExceptForOne(conf.rrdpRepositoryPath, oldEnough, sessionId)
    logger.info(s"Removing empty directories in ${conf.rrdpRepositoryPath}")
    rrdpWriter.deleteEmptyDirectories(conf.rrdpRepositoryPath)

    // Wait for the time T and delete those which are older than `now`
    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod) {
      logger.info(s"Removing all the older sessions in RRDP repository except for $sessionId")
      rrdpWriter.deleteSessionsOlderThanExceptForOne(conf.rrdpRepositoryPath, FileTime.from(now), sessionId)
      logger.info(s"Removing empty directories in ${conf.rrdpRepositoryPath}")
      rrdpWriter.deleteEmptyDirectories(conf.rrdpRepositoryPath)
    }
  }


  def updateRrdpRepositoryCleanup()(implicit session: DBSession) = {

    val oldEnough = FileTime.from(Instant.now()
      .minus(conf.unpublishedFileRetainPeriod.toMillis, ChronoUnit.MILLIS))

    // cleanup current session
    pgStore.getCurrentSessionInfo.foreach { case (version, _, _) =>
      logger.info(s"Removing snapshots from the session ${version.sessionId} older than $oldEnough and having serial number older than ${version.serial}")
      rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, oldEnough, version.serial)
    }

    // Delete version that are removed from the database
    pgStore.deleteOldVersions.groupBy(_._1).foreach {
      case (sessionId, ss) =>
        val serials = ss.map(_._2).toSet
        system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod) {
          rrdpWriter.deleteDeltas(conf.rrdpRepositoryPath, UUID.fromString(sessionId), serials)
          logger.info(s"Removing empty directories in ${conf.rrdpRepositoryPath}")
          rrdpWriter.deleteEmptyDirectories(conf.rrdpRepositoryPath)
        }
    }
  }

}
