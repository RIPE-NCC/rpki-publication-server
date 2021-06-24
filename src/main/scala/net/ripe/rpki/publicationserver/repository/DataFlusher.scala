package net.ripe.rpki.publicationserver.repository

import akka.actor.ActorSystem
import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.fs.{Rrdp, RrdpRepositoryWriter, RsyncRepositoryWriter}
import net.ripe.rpki.publicationserver.model.INITIAL_SERIAL
import net.ripe.rpki.publicationserver.store.postgresql.{DeltaInfo, PgStore, SnapshotInfo}
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

  private def updateSnapshot(sessionId: String, serial: Long)(implicit session: DBSession) = {
    val currentSession = pgStore.getCurrentSessionInfo
    val (snapshotFileName, snapshotPath, generatedSnapshotName) = currentSession match {
      case Some((sessionId_, serial_, Some(SnapshotInfo(_, _, knownName, _, _)), _))
        if (sessionId_ == sessionId && serial_ == serial) =>
          (knownName, snapshotPathKnownName(sessionId, serial, knownName), true)
      case _ =>
        val (name, path) = snapshotPathRandom(sessionId, serial)
        (name, path, true)
    }

    val (snapshotHash, snapshotSize) = withAtomicStream(snapshotPath, rrdpWriter.fileAttributes) {
      writeRrdpSnapshot(sessionId, serial, _)
    }
    val info = SnapshotInfo(sessionId, serial, snapshotFileName, snapshotHash, snapshotSize)
    if (generatedSnapshotName) {
      pgStore.updateSnapshotInfo(info)
    }
    info
  }


  private def updateDelta(sessionId: String, serial: Long)(implicit session: DBSession) = {
    val currentSession = pgStore.getCurrentSessionInfo
    val (deltaFileName, deltaPath, generatedDeltaName) = currentSession match {
      case Some((sessionId_, serial_, _, Some(DeltaInfo(_, _, knownName, _, _))))
        if (sessionId_ == sessionId && serial_ == serial) =>
          (knownName, deltaPathKnownName(sessionId, serial, knownName), false)
      case _ =>
        val (name, path) = deltaPathRandom(sessionId, serial)
        (name, path, true)
    }

    val (deltaHash, deltaSize) = {
      withAtomicStream(deltaPath, rrdpWriter.fileAttributes) {
        writeRrdpDelta(sessionId, serial, _)
      }
    }
    val deltaInfo = DeltaInfo(sessionId, serial, deltaFileName, deltaHash, deltaSize)
    if (generatedDeltaName) {
      pgStore.updateDeltaInfo(deltaInfo)
    }
    deltaInfo
  }

  private def updateRrdpFS(sessionId: String, serial: Long, latestFrozenPreviously: Option[Long])(implicit session: DBSession) = {

    // Generate snapshot for the latest serial, we are only able to general the latest snapshot
    val snapshotInfo = updateSnapshot(sessionId, serial)

    latestFrozenPreviously match {
      case None =>
        // Generate only the latest delta, it's the first time
        updateDelta(sessionId, serial)

      case Some(previous) =>
        // Catch up on deltas, i.e. generate deltas from `latestFrozenPreviously` to `serial`.
        // This is to cover the case if version freeze was initiated by some other instance
        // and this instance is lagging behind.
        for (s <- (previous + 1) to serial) {
          updateDelta(sessionId, s)
        }
    }

    val (_, d) = Time.timed {
      val deltas = pgStore.getReasonableDeltas(sessionId)
      rrdpWriter.writeNotification(conf.rrdpRepositoryPath) { os =>
        writeNotification(snapshotInfo, deltas, new HashingSizedStream(os))
      }
    }
    logger.info(s"Generated notification $sessionId/$serial, took ${d}ms")

  }

  private def initRrdpFS(sessionId: String, latestSerial: Long)(implicit session: DBSession) = {
    val snapshotInfo = updateSnapshot(sessionId, latestSerial)

    if (latestSerial > INITIAL_SERIAL) {
      // When there are changes since the last freeze the latest delta might not yet exist, so ensure it gets created.
      // The `getReasonableDeltas` query below will then decide which deltas (if any) should be included in the
      // notification.xml file.
      updateDelta(sessionId, latestSerial)
    }

    val deltas = pgStore.getReasonableDeltas(sessionId)
    for {
      DeltaInfo(_, serial, deltaFileName, _, _) <- deltas
      // Delta for the latest serial was already created above, so we can skip it here.
      if serial != latestSerial
    } {
      val deltaPath = deltaPathKnownName(sessionId, serial, deltaFileName)
      val (deltaHash, deltaSize) =
        withAtomicStream(deltaPath, rrdpWriter.fileAttributes) {
          writeRrdpDelta(sessionId, serial, _)
        }
    }

    val (_, duration) = Time.timed {
      rrdpWriter.writeNotification(conf.rrdpRepositoryPath) { os =>
        writeNotification(snapshotInfo, deltas, new HashingSizedStream(os))
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


  def writeNotification(snapshotInfo: SnapshotInfo, deltas: Seq[DeltaInfo], stream: HashingSizedStream) = {
    IOStream.string(s"""<notification version="1" session_id="${snapshotInfo.sessionId}" serial="${snapshotInfo.serial}" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    val snapshotUrl = conf.snapshotUrl(snapshotInfo)
    IOStream.string(s"""  <snapshot uri="$snapshotUrl" hash="${snapshotInfo.hash.toHex}"/>\n""", stream)
    deltas.foreach { delta =>
      val deltaUrl = conf.deltaUrl(delta)
      IOStream.string(s"""  <delta serial="${delta.serial}" uri="$deltaUrl" hash="${delta.hash.toHex}"/>\n""", stream)
    }
    IOStream.string("</notification>", stream)
  }

  private val random = new scala.util.Random(new java.security.SecureRandom())

  private def generateRandomPart: String = {
    random.alphanumeric.map(c => c.toLower).take(20).mkString
  }

  def deltaPathKnownName(sessionId: String, serial: Long, deltaFileName: String): Path = {
    Files.createDirectories(commonSubPath(sessionId, serial)).resolve(deltaFileName)
  }

  def deltaPathRandom(sessionId: String, serial: Long): (String, Path) = {
    val deltaFileName = Rrdp.deltaFileNameWithExtra(generateRandomPart)
    (deltaFileName, Files.createDirectories(commonSubPath(sessionId, serial)).resolve(deltaFileName))
  }

  def snapshotPathRandom(sessionId: String, serial: Long): (String, Path) = {
    val snapshotFileName = Rrdp.snapshotFileNameWithExtra(generateRandomPart)
    (snapshotFileName, Files.createDirectories(commonSubPath(sessionId, serial)).resolve(snapshotFileName))
  }

  def snapshotPathKnownName(sessionId: String, serial: Long, snapshotFileName: String): Path = {
    Files.createDirectories(commonSubPath(sessionId, serial)).resolve(snapshotFileName)
  }

  private def commonSubPath(sessionId: String, serial: Long) = {
    Paths.get(conf.rrdpRepositoryPath, sessionId, String.valueOf(serial))
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
      case ("UPD", Some(hash), Some(bytes)) =>
        IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}" hash="${hash.toHex}">""", stream)
        IOStream.string(Bytes.toBase64(bytes).value, stream)
        IOStream.string("</publish>\n", stream)
      case ("DEL", Some(hash), None) =>
        IOStream.string(s"""<withdraw uri="${attr(uri.toASCIIString)}" hash="${hash.toHex}"/>\n""", stream)
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
    pgStore.getCurrentSessionInfo.foreach { case (sessionId, serial, _, _) =>
      logger.info(s"Removing snapshots from the session $sessionId older than $oldEnough and having serial number older than $serial")
      rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, oldEnough, serial)
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
