package net.ripe.rpki.publicationserver.parsing

import net.ripe.rpki.publicationserver.model.BaseError

import scala.io.BufferedSource

trait MessageParser[T] {

  def Schema: String

  protected def parse(parser: StaxParser): Either[BaseError, T]

  def parse(xmlSource: BufferedSource): Either[BaseError, T] = {
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
