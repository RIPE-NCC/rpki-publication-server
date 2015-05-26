package net.ripe.rpki.publicationserver

import scala.io.BufferedSource

case class Base64(value: String)

trait MessageParser[T] {

  def Schema: String

  protected def parse(parser: StaxParser): T

  def parse(xmlSource: BufferedSource): T = {
    val reader = xmlSource.bufferedReader()
    try {
      // The StaxParser will make make sure that the message is validated against the schema while we are reading it:
      // this way our parsing code can rely on the assumption that the xml is valid
      val parser = StaxParser.createFor(reader, Schema)
      parse(parser)
    } finally {
      reader.close()
    }
  }

}
