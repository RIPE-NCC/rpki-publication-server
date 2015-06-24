package net.ripe.rpki.publicationserver.store.fs

import java.io.{File, FileWriter}
import java.nio.file._
import com.softwaremill.macwire.MacwireMacros._

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
    writeFile(snapshot.serialized, new File(stateDir, "snapshot.xml"))
  }

  def writeDelta(rootDir: String, delta: Delta) = {
    val stateDir = getStateDir(rootDir, delta.sessionId.toString, delta.serial)
    writeFile(delta.serialize.mkString, new File(stateDir, "delta.xml"))
  }

  def writeNotification(rootDir: String, notification: Notification) = {
    val root = getRootFolder(rootDir)

    val tmpFile = new File(root, "notification_tmp.xml")
    writeFile(notification.serialized, tmpFile)

    val source = Paths.get(tmpFile.toURI)
    val target = Paths.get(new File(root, "notification.xml").toURI)
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
  }

  private def writeFile(content: String, file: File) = {
    val writer = new FileWriter(file)
    try writer.write(content)
    finally writer.close()
  }

  private def getRootFolder(rootDir: String): File = {
    val root = new File(rootDir)
    if (!root.exists()) root.mkdir()
    root
  }

  private def getStateDir(rootDir: String, sessionId: String, serial: Long): File = {
    val root = getRootFolder(rootDir)
    dir(dir(root, sessionId), serial.toString)
  }

  private def dir(d: File, name: String) = {
    val _dir = new File(d, name)
    if (!_dir.exists()) _dir.mkdir()
    _dir
  }

  def deleteSessionFile(rootDir: String, serverState: ServerState, name: String) = {
    val ServerState(sessionId, serial) = serverState
    val sessionDir = new File(new File(rootDir), sessionId.toString)
    val serialDir = new File(sessionDir, serial.toString)
    del(new File(serialDir, "snapshot.xml"))
  }

  def del(f: File) = Files.deleteIfExists(Paths.get(f.toURI))

  def deleteSnapshot(rootDir: String, serverState: ServerState) = deleteSessionFile(rootDir, serverState, "snapshot.xml")

  def deleteDelta(rootDir: String, serverState: ServerState) = deleteSessionFile(rootDir, serverState, "delta.xml")

  def deleteNotification(rootDir: String) = {
    del(new File(rootDir, "notification.xml"))
    del(new File(rootDir, "notification_tmp.xml"))
  }
}
