package net.ripe.rpki.publicationserver

import java.io.BufferedReader
import java.net.URI
import java.util.UUID

import scala.annotation.tailrec
import scala.io.{BufferedSource, Source}

case class Snapshot(sessionId: UUID, serial: String, elements: Seq[PublishElement])
case class PublishElement(uri: URI, hash: Option[String], body: Base64)

class RrdpMessageParser extends MessageParser {

  case class MsgError(message: String)

  val Schema = Source.fromURL(getClass.getResource("/rrdp-schema.rng")).mkString

  def process(xmlSource: BufferedSource): Snapshot = {

    val SNAPSHOT = "snapshot"
    val PUBLISH = "publish"

    def parse(parser: StaxParser): Snapshot = {

      var serial: String = null
      var sessionId: UUID = null

      def captureSnapshotParameters(attrs: Map[String, String]): Unit = {
        assert(attrs("version") == "1", "The version attribute in the notification root element MUST be 1")
        assert(attrs("serial").matches("[1-9][0-9]*"), s"The serial attribute must be an unbounded, unsigned positive integer [${attrs("serial")}]")
        serial = attrs("serial")
        sessionId = UUID.fromString(attrs("session_id"))
      }

      @tailrec
      def parseNext(lastAttributes: Map[String, String], lastText: String, elements: Seq[PublishElement]): Seq[PublishElement] = {

        assert(parser.hasNext, s"The snapshot file does not contain a complete $SNAPSHOT element")

        parser.next match {

            case ElementStart(label, attrs) =>
              if (SNAPSHOT == label.toLowerCase) {
                captureSnapshotParameters(attrs)
              }
              parseNext(attrs, null, elements)

            case ElementEnd(label) =>
              label.toLowerCase match {
                case SNAPSHOT =>
                  elements

                case PUBLISH =>
                  val element = new PublishElement(uri = URI.create(lastAttributes("uri")), hash = lastAttributes.get("hash"), body = Base64.apply(lastText))
                  parseNext(null, null, element +: elements)
              }

            case ElementText(newText) =>
              parseNext(lastAttributes, newText, elements)

            case _ => parseNext(lastAttributes, lastText, elements)
          }
      }

      val publishElements: Seq[PublishElement] = parseNext(null, null, Seq())
      Snapshot(sessionId, serial, publishElements)
    }

    val reader: BufferedReader = xmlSource.bufferedReader()
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
