package net.ripe.rpki.publicationserver.fs

import net.ripe.rpki.publicationserver.{Notification, NotificationParser, RrdpParser, SnapshotState}

import scala.io.Source

object SnapshotReader {
  def readSnapshot(repositoryPath: String, repositoryUri: String) : SnapshotState = {

    def composeSnapshotPath(notification: Notification): String = {
      assert(notification.snapshot.uri.startsWith(repositoryUri), s"Snapshot URI [${notification.snapshot.uri}] in notification.xml does not start with configured repository URI [$repositoryUri]")
      val relativePath = notification.snapshot.uri.stripPrefix(repositoryUri)
      s"$repositoryPath/$relativePath"
    }

    // read notification.xml first
    val notification: Notification = NotificationParser.parse(Source.fromFile(s"$repositoryPath/notification.xml"))

    RrdpParser.parse(Source.fromFile(composeSnapshotPath(notification)))
  }
}
