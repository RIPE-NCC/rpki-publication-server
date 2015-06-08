package net.ripe.rpki.publicationserver.fs

import java.io.{File, FileWriter}
import java.nio.file._
import java.util.UUID

import net.ripe.rpki.publicationserver._

import scala.util.{Failure, Try}
import scala.xml.Elem

class RepositoryWriter extends Logging {

  def writeNewState(rootDir: String, newSnapshot: RepositoryState, newNotification: Notification) = {
    Try {
      writeSnapshot(rootDir, newSnapshot)
      try {
        newSnapshot.latestDelta match {
          case None =>
            val message = s"Could not find the latest delta, sessionId=${newSnapshot.sessionId}, serial=${newSnapshot.serial}"
            logger.error(message)
            throw new IllegalStateException(message)
          case Some(delta) =>
            writeDelta(rootDir, delta)
        }
        try {
          writeNotification(rootDir, newNotification)
        } catch {
          case e: Exception =>
            logger.error("Could not write notification file: ", e)
            deleteNotification(rootDir, newSnapshot)
            throw e
        }
      } catch {
        case e: Exception =>
          logger.error("An error occurred, removing delta: ", e)
          deleteDelta(rootDir, newSnapshot)
          throw e
      }
    }.recoverWith { case e : Exception =>
        logger.error("An error occurred, removing snapshot: ", e)
        deleteSnapshot(rootDir, newSnapshot)
        Failure(e)
    }
  }

  def writeSnapshot(rootDir: String, snapshot: RepositoryState) = {
    val stateDir = getStateDir(rootDir, snapshot.sessionId, snapshot.serial)
    writeFile(snapshot.serialize, new File(stateDir, "snapshot.xml"))
  }

  def writeDelta(rootDir: String, delta: Delta) = {
    val stateDir = getStateDir(rootDir, delta.sessionId, delta.serial)
    writeFile(delta.serialize, new File(stateDir, "delta.xml"))
  }

  def writeNotification(rootDir: String, notification: Notification) = {
    val root = getRootFolder(rootDir)

    val tmpFile = new File(root, "notification_tmp.xml")
    writeFile(notification.serialize, tmpFile)

    val source = Paths.get(tmpFile.toURI)
    val target = Paths.get(new File(root, "notification.xml").toURI)
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
  }

  private def writeFile(elem: Elem, file: File) = {
    val writer = new FileWriter(file)
    try writer.write(elem.mkString)
    finally writer.close()
  }

  private def getRootFolder(rootDir: String): File = {
    val root = new File(rootDir)
    if (!root.exists()) root.mkdir()
    root
  }

  private def getStateDir(rootDir: String, sessionId: UUID, serial: BigInt): File = {
    val root = getRootFolder(rootDir)
    dir(dir(root, sessionId.toString), serial.toString())
  }

  private def dir(d: File, name: String) = {
    val _dir = new File(d, name)
    if (!_dir.exists()) _dir.mkdir()
    _dir
  }

  def deleteSessionFile(rootDir: String, snapshot: RepositoryState, name: String) = {
    val sessionDir = new File(new File(rootDir), snapshot.sessionId.toString)
    val serialDir = new File(sessionDir, snapshot.serial.toString)
    del(new File(serialDir, "snapshot.xml"))
  }

  def del(f: File) = Files.deleteIfExists(Paths.get(f.toURI))

  def deleteSnapshot(rootDir: String, snapshot: RepositoryState) = deleteSessionFile(rootDir, snapshot, "snapshot.xml")

  def deleteDelta(rootDir: String, snapshot: RepositoryState) = deleteSessionFile(rootDir, snapshot, "delta.xml")

  def deleteNotification(rootDir: String, snapshot: RepositoryState) = {
    del(new File(rootDir, "notification.xml"))
    del(new File(rootDir, "notification_tmp.xml"))
  }

}
