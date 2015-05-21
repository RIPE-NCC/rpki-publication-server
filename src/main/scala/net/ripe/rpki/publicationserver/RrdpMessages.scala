package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.UUID

import scala.annotation.tailrec
import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success, Try}
import scala.xml._

case class SnapshotAttributes(sessionId: UUID, serial: String) {
  assert(serial.matches("[1-9][0-9]*"), s"The serial attribute must be an unbounded, unsigned positive integer [$serial]")
}

case class PublishElement(uri: URI, hash: String, content: Base64)

class RrdpMessageParser extends MessageParser {

  case class MsgError(message: String)
  case class PublishElement(uri: URI, hash: Option[String], body: Base64)

  val Schema = Source.fromURL(getClass.getResource("/rrdp-schema.rng")).mkString

  val RootElement = "snapshot"

  private def parseSnapshotAttributes(attrs: Map[String, String]) = Try[SnapshotAttributes] {
    assert(attrs("version") == "1", "The version attribute in the notification root element MUST be 1")
    val sessionId: UUID = UUID.fromString(attrs("session_id"))
    SnapshotAttributes(sessionId, attrs("serial"))
  }

  def process(xmlSource: BufferedSource): Either[MsgError, Seq[PublishElement]] = {

    // PublishElement(URI.create(attrs("uri")), attrs("hash"), Base64(text))

    val SNAPSHOT = "snapshot"
    val PUBLISH = "publish"

    def parse(parser: StaxParser): Either[MsgError, Seq[PublishElement]] = {
      @tailrec
      def parseNext(lastAttributes: Map[String, String], lastText: String, elements: Seq[PublishElement]): Either[MsgError, Seq[PublishElement]] = {
        if (!parser.hasNext) {
          Left(MsgError(s"The snapshot file does not contain a complete $SNAPSHOT element"))
        } else {
          parser.next match {

            case ElementStart(label, attrs) =>
              parseNext(attrs, null, elements)

            case ElementEnd(label) =>
              label.toLowerCase match {
                case SNAPSHOT =>
                  Right(elements)

                case PUBLISH =>
                  val element = new PublishElement(uri = URI.create(lastAttributes("uri")), hash = lastAttributes.get("hash"), body = Base64.apply(lastText))
                  parseNext(null, null, element +: elements)
              }

            case ElementText(newText) =>
              parseNext(lastAttributes, newText, elements)

            case _ => parseNext(lastAttributes, lastText, elements)
          }
        }
      }

      parseNext(null, null, Seq())
    }
    // The StaxParser will make make sure that the message is validated against the schema while we are reading it:
    // this way our parsing code can rely on the assumption that the xml is valid
    val parser = StaxParser.createFor(xmlSource.bufferedReader(), Schema)

    parse(parser)

  }
}
