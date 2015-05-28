package net.ripe.rpki.publicationserver

import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, Matchers, FunSuite}

import scala.io.Source

abstract class PublicationServerBaseSpec extends FunSuite with BeforeAndAfter with Matchers with MockitoSugar with TestLogSetup {

  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))

  def trim(s: String): String = s.filterNot(_.isWhitespace)
}
