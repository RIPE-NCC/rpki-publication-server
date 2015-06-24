package net.ripe.rpki.publicationserver.store.fs

import java.nio.file._

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.{Snapshot, ServerState, Notification, Delta}

import scala.util.{Failure, Try}

class RepositoryWriter extends Logging {

  def writeNewState(rootDir: String, serverState: ServerState, deltas: Seq[Delta], newNotification: Notification, snapshot: Snapshot) =
    Try {
      writeSnapshot(rootDir, serverState, snapshot)
      try {
        if (deltas.nonEmpty) {
          val latestDelta = deltas.maxBy(_.serial)
          writeDelta(rootDir, latestDelta)
        } else {
          logger.info("No deltas found in current snapshot")
        }
        try {
          writeNotification(rootDir, newNotification)
        } catch {
          case e: Exception =>
            logger.error("Could not write notification file: ", e)
            deleteNotification(rootDir)
            throw e
        }
      } catch {
        case e: Exception =>
          logger.error("An error occurred, removing delta: ", e)
          deleteDelta(rootDir, serverState)
          throw e
      }
    }.recoverWith { case e: Exception =>
      logger.error("An error occurred, removing snapshot: ", e)
      deleteSnapshot(rootDir, serverState)
      Failure(e)
    }

  def writeSnapshot(rootDir: String, serverState: ServerState, snapshot: Snapshot) = {
    val ServerState(sessionId, serial) = serverState
    val stateDir = getStateDir(rootDir, sessionId.toString, serial)
    writeFile(snapshot.serialized, stateDir.resolve("snapshot.xml"))
  }

  def writeDelta(rootDir: String, delta: Delta) = {
    val stateDir = getStateDir(rootDir, delta.sessionId.toString, delta.serial)
    writeFile(delta.serialize.mkString, stateDir.resolve("delta.xml"))
  }

  def writeNotification(rootDir: String, notification: Notification) = {
    val root = getRootFolder(rootDir)

    val tmpFile = Files.createTempFile(root, "notification.", ".xml")
    try {
      writeFile(notification.serialized, tmpFile)
      val target = root.resolve("notification.xml")
      Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  private def writeFile(content: String, path: Path) = {
    Files.write(path, content.getBytes("UTF-8"))
  }

  private def getRootFolder(rootDir: String): Path = {
    Files.createDirectories(Paths.get(rootDir))
  }

  private def getStateDir(rootDir: String, sessionId: String, serial: Long): Path = {
    Files.createDirectories(Paths.get(rootDir, sessionId, String.valueOf(serial)))
  }

  def deleteSessionFile(rootDir: String, serverState: ServerState, name: String) = {
    val ServerState(sessionId, serial) = serverState
    Files.deleteIfExists(Paths.get(rootDir, sessionId.toString, serial.toString, "snapshot.xml"))
  }

  def deleteSnapshot(rootDir: String, serverState: ServerState) = deleteSessionFile(rootDir, serverState, "snapshot.xml")

  def deleteDelta(rootDir: String, serverState: ServerState) = deleteSessionFile(rootDir, serverState, "delta.xml")

  def deleteNotification(rootDir: String) = {
    Files.deleteIfExists(Paths.get(rootDir, "notification.xml"))
  }
}
