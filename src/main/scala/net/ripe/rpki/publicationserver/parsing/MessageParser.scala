package net.ripe.rpki.publicationserver.parsing

import net.ripe.rpki.publicationserver.model.BaseError

import java.io.{BufferedReader, ByteArrayInputStream, InputStreamReader}
import scala.io.BufferedSource

trait MessageParser[T] {

  def Schema: String

  protected def parse(parser: StaxParser): Either[BaseError, T]

  def parse(xmlSource: Array[Byte]): Either[BaseError, T] = {
    val reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(xmlSource)))
    parseFromReader(reader)
  }

  def parse(xmlSource: BufferedSource): Either[BaseError, T] = {
    parseFromReader(xmlSource.bufferedReader())
  }

  private def parseFromReader(reader: BufferedReader) = {
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
