package net.ripe.rpki.publicationserver.fs

import java.io.{FileWriter, File}
import java.nio.file._

import net.ripe.rpki.publicationserver.{Notification, SnapshotState}

class RepositoryWriter {
  def writeSnapshot(rootDir: String, snapshot: SnapshotState) = {
    val root = getRootFolder(rootDir)

    val sessionDir = new File(root, snapshot.sessionId.toString)
    if (!sessionDir.exists()) sessionDir.mkdir()

    val serialDir = new File(sessionDir, snapshot.serial.toString())
    serialDir.mkdir()

    val snapshotFile = new File(serialDir, "snapshot.xml")
    val writer = new FileWriter(snapshotFile)
    try writer.write(snapshot.serialize.mkString)
    finally writer.close()
  }

  def writeNotification(rootDir: String, notification: Notification) = {
    val root = getRootFolder(rootDir)

    val notificationFile = new File(root, "notification_tmp.xml")
    val writer = new FileWriter(notificationFile)
    try writer.write(notification.serialize.mkString)
    finally writer.close()

    val source = Paths.get(notificationFile.toURI)
    val target = Paths.get(new File(root, "notification.xml").toURI)
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
  }

  private def getRootFolder(rootDir: String): File = {
    val root = new File(rootDir)
    if (!root.exists()) root.mkdir()
    root
  }
}