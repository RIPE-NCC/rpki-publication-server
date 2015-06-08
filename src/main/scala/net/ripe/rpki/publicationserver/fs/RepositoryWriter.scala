package net.ripe.rpki.publicationserver.fs

import java.io.{FileWriter, File}
import java.nio.file._
import java.util.UUID

import net.ripe.rpki.publicationserver._

import scala.xml.Elem

class RepositoryWriter extends Logging {

  def writeNewState(rootDir: String, newSnapshot: RepositoryState, newNotification: Notification) = {
    writeSnapshot(rootDir, newSnapshot)
    newSnapshot.latestDelta match {
      case None => logger.error(s"Could not find the latest delta, sessionId=${newSnapshot.sessionId}, serial=${newSnapshot.serial}")
      case Some(delta) => writeDelta(rootDir, delta)
    }
    writeNotification(rootDir, newNotification)
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


//  def getSnapshotWriteAction(root: String, state: RepositoryState): FsAction[File] = {
//    val dir = root + "/" + state.sessionId + "/" + state.serial
//    MkDir(dir) { d =>
//      WriteFile(new File(d, "snapshot.xml"), state.serialize.mkString).execute()
//    }
//  }

}
