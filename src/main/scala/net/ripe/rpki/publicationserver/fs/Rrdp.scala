package net.ripe.rpki.publicationserver.fs

object Rrdp {
  val notificationFilename = "notification.xml"

  def deltaFileNameWithExtra(s: String) = s"delta-$s.xml"
  def snapshotFileNameWithExtra(s: String) = s"snapshot-$s.xml"

  def isDelta(f: String): Boolean =
    f.startsWith("delta-") && f.endsWith(".xml")

  def isSnapshot(f: String): Boolean =
    f.startsWith("snapshot-") && f.endsWith(".xml")
}
