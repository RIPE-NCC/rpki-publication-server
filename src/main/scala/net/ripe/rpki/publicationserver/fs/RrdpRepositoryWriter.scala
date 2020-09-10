package net.ripe.rpki.publicationserver.fs

import java.io.{FileOutputStream, OutputStream}
import java.nio.file._
import java.nio.file.attribute.{FileTime, PosixFilePermissions}
import java.util.UUID

import net.ripe.rpki.publicationserver._

import scala.util.Try
import scala.collection.parallel.CollectionConverters._

class RrdpRepositoryWriter extends Logging {

  def createSession(rootDir: String, sessionId: UUID, serial: Long): Unit = {
    logger.info(s"Initializing session directory: $rootDir, session-id = $sessionId, serial = $serial")
    Files.createDirectories(Paths.get(rootDir, sessionId.toString, String.valueOf(serial)))
  }

  def cleanRepositoryExceptOneSessionOlderThan(rootDir: String, timestamp: FileTime, sessionId: UUID): Path = {
    Files.walkFileTree(Paths.get(rootDir), new RemoveAllVisitorExceptOneSession(sessionId.toString, timestamp))
  }

  private val fileAttributes = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"))

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

  def deleteSessionFile(rootDir: String, sessionId: UUID, serial: Long, name: String): Boolean = {
    Files.deleteIfExists(Paths.get(rootDir, sessionId.toString, serial.toString, name))
  }

  def deleteSnapshotsOlderThan(rootDir: String, timestamp: FileTime, latestSerial: Long): Unit = {
    Files.walkFileTree(Paths.get(rootDir), new RemovingFileVisitor(timestamp, Paths.get(Rrdp.snapshotFilename), latestSerial))
  }

  def cleanUpEmptyDir(rootDir: String, sessionId: UUID, serial: Long) = {
    val dir = Paths.get(rootDir, sessionId.toString, serial.toString)
    if (FSUtil.isEmptyDir(dir)) {
      Files.deleteIfExists(dir)
    }
  }

  def deleteDelta(rootDir: String, sessionId: UUID, serial: Long): AnyVal = {
    deleteSessionFile(rootDir, sessionId, serial, Rrdp.deltaFilename)
    cleanUpEmptyDir(rootDir, sessionId, serial)
  }
  def deleteDeltas(rootDir: String, sessionId: UUID, serials: Iterable[Long]): Unit =
    serials.par.foreach(s => deleteDelta(rootDir, sessionId, s))
}