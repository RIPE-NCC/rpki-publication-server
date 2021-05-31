package net.ripe.rpki.publicationserver.fs

import net.ripe.rpki.publicationserver._

import java.io.{FileOutputStream, OutputStream}
import java.nio.file._
import java.nio.file.attribute.{FileTime, PosixFilePermissions}
import java.util.UUID
import scala.collection.parallel.CollectionConverters._
import scala.util.Try

class RrdpRepositoryWriter extends Logging {

  def createSession(rootDir: String, sessionId: UUID, serial: Long): Unit = {
    logger.info(s"Initializing session directory: $rootDir, session-id = $sessionId, serial = $serial")
    Files.createDirectories(Paths.get(rootDir, sessionId.toString, String.valueOf(serial)))
  }

  val fileAttributes: FileAttributes = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"))

  def writeNotification(rootDir: String)(writeF : OutputStream => Unit): Option[FileTime] = {
    val root = getRootFolder(rootDir)
    val tmpFile = Files.createTempFile(root, "notification.", ".xml", fileAttributes)
    try {
      writeF(new FileOutputStream(tmpFile.toFile))
      val target = root.resolve(Rrdp.notificationFilename)
      val previousNotificationTimestamp = Try(Files.getLastModifiedTime(target)).toOption
      Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      previousNotificationTimestamp
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  private def getRootFolder(rootDir: String): Path = Files.createDirectories(Paths.get(rootDir))


  def deleteSnapshotsOlderThan(rootDir: String, timestamp: FileTime, latestSerial: Long): Unit = {
    Files.walkFileTree(Paths.get(rootDir), new RemovingFileVisitor(RrdpRepositoryWriter.snapshotToDelete(timestamp, latestSerial)))
  }

  def deleteSessionsOlderThanExceptForOne(rootDir: String, timestamp: FileTime, sessionId: UUID): Path =
    Files.walkFileTree(Paths.get(rootDir), new RemoveAllVisitorExceptOneSession(sessionId.toString, timestamp))

  def deleteEmptyDirectories(rootDir: String): Unit =
    Files.walkFileTree(Paths.get(rootDir), new RemoveEmptyDirectoriesVisitor())

  def deleteDelta(rootDir: String, sessionId: UUID, serial: Long): AnyVal = {
    val sessionSerialDir = Paths.get(rootDir, sessionId.toString, serial.toString)
    Files.list(sessionSerialDir).
      filter(Rrdp.isDelta).
      forEach(f => {
        val deltaPath = sessionSerialDir.resolve(f)
        logger.debug(s"Deleting delta file $deltaPath")
        Files.deleteIfExists(deltaPath)
      })
    if (sessionSerialDir.toFile.exists() && FSUtil.isEmptyDir(sessionSerialDir)) {
      Files.deleteIfExists(sessionSerialDir)
    }
  }

  def deleteDeltas(rootDir: String, sessionId: UUID, serials: Set[Long]): Unit =
    serials.par.foreach(s => deleteDelta(rootDir, sessionId, s))
}

object RrdpRepositoryWriter {

  def snapshotToDelete(timestamp: FileTime, latestSerial: Long)(file: Path) = {
    // we expect snapshots to be in a directory structure like this
    // $session-id/$serial/snapshot-blabla.xml
    // this expression means "serial != latestSerial"
    lazy val notTheLastSerial = Try {
      file.getParent.getFileName.toString.toLong
    }.toEither.
      map(p => p != latestSerial).
      getOrElse(true)

    Rrdp.isSnapshot(file) && FSUtil.isModifiedBefore(file, timestamp) && notTheLastSerial
  }
}

