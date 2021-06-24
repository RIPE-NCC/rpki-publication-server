package net.ripe.rpki.publicationserver.fs

import java.nio.file.Path

object Rrdp {
  val notificationFilename = "notification.xml"

  def deltaFileNameWithExtra(s: String) = s"delta-$s.xml"
  def snapshotFileNameWithExtra(s: String) = s"snapshot-$s.xml"

  def isSnapshot(f: Path): Boolean = isSpecificXml(f, "snapshot")
  def isDelta(f: Path): Boolean =  isSpecificXml(f, "delta")

  private def isSpecificXml(f: Path, prefix: String) = {
    val localName = f.getFileName.toString
    localName.startsWith(prefix + "-") && localName.endsWith(".xml")
  }
}
