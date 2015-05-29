package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.UUID

import scala.annotation.tailrec
import scala.io.Source

case class PublishElement(uri: URI, hash: Hash, body: Base64)

object RrdpParser extends MessageParser[SnapshotState] with Hashing {

  override val Schema = Source.fromURL(getClass.getResource("/rrdp-schema.rng")).mkString

  private val SNAPSHOT = "snapshot"
  private val PUBLISH = "publish"

  private def captureSnapshotParameters(attrs: Map[String, String]) = {
    assert(attrs("version") == "1", "The version attribute in the notification root element MUST be 1")
    assert(attrs("serial").matches("[1-9][0-9]*"), s"The serial attribute [${attrs("serial")}] must be an unbounded, unsigned positive integer")
    (BigInt(attrs("serial")), UUID.fromString(attrs("session_id")))
  }

  override protected def parse(parser: StaxParser): SnapshotState = {
    var serial: BigInt = null
    var sessionId: UUID = null

    @tailrec
    def parseNext(lastAttributes: Map[String, String], lastText: String, elements: Seq[PublishElement]): Seq[PublishElement] = {

      assert(parser.hasNext, s"The snapshot file does not contain a complete $SNAPSHOT element")

      parser.next match {

        case ElementStart(label, attrs) =>
          if (SNAPSHOT == label.toLowerCase) {
            val p = captureSnapshotParameters(attrs)
            serial = p._1
            sessionId = p._2
          }
          parseNext(attrs, "", elements)

        case ElementEnd(label) =>
          label.toLowerCase match {
            case SNAPSHOT =>
              elements

            case PUBLISH =>
              val myBody = Base64(normalize(lastText))
              val myHash = lastAttributes.getOrElse("hash", hash(myBody).hash)
              val element = PublishElement(uri = URI.create(lastAttributes("uri")), hash = Hash(myHash), body = myBody)
              parseNext(null, null, element +: elements)
          }

        case ElementText(newText) =>
          parseNext(lastAttributes, lastText + newText, elements)

        case _ => parseNext(lastAttributes, lastText, elements)
      }
    }

    val publishElements: Seq[PublishElement] = parseNext(null, null, Seq())

    val publishElementsMap = publishElements.map(p => p.uri -> (p.body, p.hash)).toMap

    new SnapshotState(sessionId, serial, publishElementsMap)
  }

  private def normalize(s: String) = s.filterNot(Character.isWhitespace)

}
