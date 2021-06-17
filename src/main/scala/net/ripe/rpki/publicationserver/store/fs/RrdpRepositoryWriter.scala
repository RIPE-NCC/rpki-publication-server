package net.ripe.rpki.publicationserver.store.fs

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}

import java.nio.file._
import java.nio.file.attribute.{FileTime, PosixFilePermissions}
import java.util.UUID
import scala.util.{Failure, Try}
import scala.xml.{Node, XML}

class RrdpRepositoryWriter(conf: AppConfig) extends Logging {

  def createSession(rootDir: String, sessionId: UUID, serial: Long): Unit = {
    logger.info(s"Initializing session directory: $rootDir, session-id = $sessionId, serial = $serial")
    getStateDir(rootDir, sessionId.toString, serial)
  }

  def cleanRepositoryExceptOneSessionOlderThan(rootDir: String, timestamp: FileTime, sessionId: UUID): Path = {
    Files.walkFileTree(Paths.get(rootDir), new RemoveAllVisitorExceptOneSession(sessionId.toString, timestamp))
  }

  private val fileAttributes = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"))

  def writeNewState(rootDir: String, serverState: ServerState, newNotification: Notification, snapshot: Snapshot): Try[Option[FileTime]] =
    writeNextState(rootDir, serverState, newNotification, snapshot, () => ())

  def writeNewStateWithNotificationDelay(rootDir: String, serverState: ServerState, newNotification: Notification, snapshot: Snapshot): Try[Option[FileTime]] =
    writeNextState(rootDir, serverState, newNotification, snapshot, () => delayNotificationXml())

  private def writeNextState(rootDir: String,
                             serverState: ServerState,
                             newNotification: Notification,
                             snapshot: Snapshot,
                             maybeDelay: () => Unit) = {
    Try {
      writeSnapshot(rootDir, serverState, snapshot)
      maybeDelay()
      writeNotification(rootDir, newNotification)
    }.recoverWith { case e: Exception =>
      logger.error("An error occurred, removing snapshot: ", e)
      e.printStackTrace()
      deleteSnapshot(rootDir, serverState)
      Failure(e)
    }
  }

  // Delay writing notification.xml to avoid the 404 caching for deltas and snapshot xml files.
  private def delayNotificationXml(): Unit =
    if (conf.notificationWritingDelay > 0) {
      Thread.sleep(conf.notificationWritingDelay * 1000)
    }

  def writeSnapshot(rootDir: String, serverState: ServerState, snapshot: Snapshot): Path = {
    val ServerState(sessionId, serial) = serverState
    val stateDir = getStateDir(rootDir, sessionId.toString, serial)
    writeFile(snapshot.bytes, stateDir.resolve(Rrdp.snapshotFilename))
  }

  def writeDelta(rootDir: String, delta: Delta): Path = {
    val stateDir = getStateDir(rootDir, delta.sessionId.toString, delta.serial)
    writeFile(delta.bytes, stateDir.resolve(Rrdp.deltaFilename))
  }

  def writeNotification(rootDir: String, notification: Notification): Option[FileTime] = {
    val root = getRootFolder(rootDir)
    val tmpFile = Files.createTempFile(root, "notification.", ".xml", fileAttributes)
    try {
      writeFile(notification.serialize, tmpFile)
      val target = root.resolve(Rrdp.notificationFilename)
      val previousNotificationTimestamp = Try(Files.getLastModifiedTime(target)).toOption
      Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      previousNotificationTimestamp
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  private def writeFile(content: Array[Byte], path: Path) =
    Files.write(path, content)

  private def writeFile(content: Node, path: Path): Unit =
    XML.save(path.toString, content, "UTF-8")

  private def getRootFolder(rootDir: String): Path =
    Files.createDirectories(Paths.get(rootDir))

  private def getStateDir(rootDir: String, sessionId: String, serial: Long): Path = {
    Files.createDirectories(Paths.get(rootDir, sessionId, String.valueOf(serial)))
  }

  def deleteSessionFile(rootDir: String, serverState: ServerState, name: String): Boolean = {      
    val ServerState(sessionId, serial) = serverState
    Files.deleteIfExists(Paths.get(rootDir, sessionId.toString, serial.toString, name))
  }

  def deleteSnapshotsOlderThan(rootDir: String, timestamp: FileTime, latestSerial: Long): Unit = {
    Files.walkFileTree(Paths.get(rootDir), new RemovingFileVisitor(timestamp, Paths.get(Rrdp.snapshotFilename), latestSerial))
  }

  def deleteDeltaOlderThan(rootDir: String, timestamp: FileTime, latestSerial: Long): Unit = {
    Files.walkFileTree(Paths.get(rootDir), new RemovingFileVisitor(timestamp, Paths.get(Rrdp.deltaFilename), latestSerial))
  }

  def deleteSnapshot(rootDir: String, serverState: ServerState): Boolean = {
    deleteSessionFile(rootDir, serverState, Rrdp.snapshotFilename)
  }

  def cleanUpEmptyDir(rootDir: String, serverState: ServerState): AnyVal = {
    val ServerState(sessionId, serial) = serverState
    val dir = Paths.get(rootDir, sessionId.toString, serial.toString)
    if (FSUtil.isEmptyDir(dir)) {
      Files.deleteIfExists(dir)
    }
  }

  def deleteDelta(rootDir: String, serverState: ServerState): AnyVal = {
    deleteSessionFile(rootDir, serverState, Rrdp.deltaFilename)
    cleanUpEmptyDir(rootDir, serverState)
  }

  def deleteNotification(rootDir: String): Boolean =
    Files.deleteIfExists(Paths.get(rootDir, Rrdp.notificationFilename))

  def deleteDeltas(rootDir: String, deltas: Iterable[Delta]): Unit =
    deltas.foreach { d =>
      deleteDelta(rootDir, ServerState(d.sessionId, d.serial))
    }

  def deleteDeltas(rootDir: String, sessionId: UUID, serials: Iterable[Long]): Unit =
    serials.par.foreach(s => deleteDelta(rootDir, ServerState(sessionId, s)))
}
