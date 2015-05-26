package net.ripe.rpki.publicationserver.fs

import java.io.File
import java.nio.file.Paths

import net.ripe.rpki.publicationserver.{Notification, RrdpParser, SnapshotState}

import scala.io.Source

object SnapshotReader {
  def readSnapshot(repositoryPath: String) : SnapshotState = {
    // read notification.xml first
    val lines = Source.fromFile("notification.xml").mkString

    null

  }
}
