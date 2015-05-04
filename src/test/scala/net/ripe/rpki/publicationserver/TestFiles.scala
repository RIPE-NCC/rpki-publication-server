package net.ripe.rpki.publicationserver

import scala.io.Source

trait TestFiles {
  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))
}
