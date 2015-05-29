package net.ripe.rpki.publicationserver.fs

import java.io.File

import net.ripe.rpki.publicationserver.{Notification, NotificationParser, RrdpParser, SnapshotState}
import org.slf4j.LoggerFactory

import scala.io.Source

object SnapshotReader {
  val logger = LoggerFactory.getLogger("SnapshotReader")
  
  def readSnapshotFromNotification(repositoryPath: String, repositoryUri: String) : Option[SnapshotState] = {

    def composeSnapshotPath(notification: Notification): String = {
      assert(notification.snapshot.uri.startsWith(repositoryUri), s"Snapshot URI [${notification.snapshot.uri}] in notification.xml does not start with configured repository URI [$repositoryUri]")
      val relativePath = notification.snapshot.uri.stripPrefix(repositoryUri)
      s"$repositoryPath/$relativePath"
    }

    val notificationPath = s"$repositoryPath/notification.xml"
    val notificationFile = new File(notificationPath) 
    if (!notificationFile.exists()) {
      logger.warn(s"No previous notification.xml found in $notificationPath.")
      None
    } else {
      val notification: Notification = NotificationParser.parse(Source.fromFile(s"$repositoryPath/notification.xml"))

      Some(RrdpParser.parse(Source.fromFile(composeSnapshotPath(notification))))
    }
  }
}
