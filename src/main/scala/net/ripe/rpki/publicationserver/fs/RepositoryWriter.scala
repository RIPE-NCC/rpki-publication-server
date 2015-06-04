package net.ripe.rpki.publicationserver.fs

import java.io.{FileWriter, File}
import java.nio.file._
import java.util.UUID

import net.ripe.rpki.publicationserver.{Delta, DeltaLocator, Notification, SnapshotState}

import scala.xml.Elem

class RepositoryWriter {
  def writeSnapshot(rootDir: String, snapshot: SnapshotState) = {
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

}
