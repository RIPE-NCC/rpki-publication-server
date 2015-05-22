package net.ripe.rpki.publicationserver.fs

import net.ripe.rpki.publicationserver.{SnapshotState, MsgError}

import scala.io.Source


object SnapshotReader {
  def readSnapshot(repositoryPath: String) : Either[MsgError, SnapshotState] = {
    // read notification.xml first
    val lines = Source.fromFile("notification.xml").mkString

    null

  }

}
