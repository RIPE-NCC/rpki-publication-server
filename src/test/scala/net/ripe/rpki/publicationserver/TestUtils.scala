package net.ripe.rpki.publicationserver

import scala.io.Source

trait TestUtils {
  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))
  
  def trim(s: String): String = s.filterNot(c => c == ' ' || c == '\n')

}
