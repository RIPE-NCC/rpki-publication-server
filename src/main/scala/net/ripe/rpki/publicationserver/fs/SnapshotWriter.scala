package net.ripe.rpki.publicationserver.fs

import java.io.{FileWriter, File}

import net.ripe.rpki.publicationserver.SnapshotState

class SnapshotWriter {
  def writeSnapshot(rootDir: String, snapshot: SnapshotState) = {
    val root = new File(rootDir)
    if (!root.exists()) root.mkdir()

    val sessionDir = new File(root, snapshot.sessionId.toString)
    if (!sessionDir.exists()) sessionDir.mkdir()

    val serialDir = new File(sessionDir, snapshot.serial.toString())
    serialDir.mkdir()

    val snapshotFile = new File(serialDir, "snapshot.xml")
    val writer = new FileWriter(snapshotFile)
    try writer.write(snapshot.serialize.mkString) finally writer.close()
  }
}
