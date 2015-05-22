package net.ripe.rpki.publicationserver

import java.io.BufferedReader
import java.net.URI
import java.util.UUID

import scala.annotation.tailrec
import scala.io.{BufferedSource, Source}

case class PublishElement(uri: URI, hash: Hash, body: Base64)

class RrdpParser extends MessageParser {

  val Schema = Source.fromURL(getClass.getResource("/rrdp-schema.rng")).mkString

  private val SNAPSHOT = "snapshot"
  private val PUBLISH = "publish"
  private val NOTIFICATION = "notification"

  def parseSnapshot(xmlSource: BufferedSource): SnapshotState =
    doParse(xmlSource, Schema, parseSnapshot)


  def captureSnapshotParameters(attrs: Map[String, String]) = {
    assert(attrs("version") == "1", "The version attribute in the notification root element MUST be 1")
    assert(attrs("serial").matches("[1-9][0-9]*"), s"The serial attribute must be an unbounded, unsigned positive integer [${attrs("serial")}]")
    (BigInt(attrs("serial")), UUID.fromString(attrs("session_id")))
  }

  private def parseSnapshot(parser: StaxParser): SnapshotState = {
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
              val myHash = lastAttributes.getOrElse("hash", SnapshotState.hash(myBody).hash)
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


  def parseNotification(xmlSource: BufferedSource): SnapshotState =
    doParse(xmlSource, Schema, parseNotification)


  def parseNotification(parser: StaxParser): SnapshotState = {
    var serial: BigInt = null
    var sessionId: UUID = null

    @tailrec
    def parseNext(lastAttributes: Map[String, String], lastText: String, elements: Seq[PublishElement]): Seq[PublishElement] = {

      assert(parser.hasNext, s"The notification.xml file does not contain a complete $NOTIFICATION element")

      parser.next match {
        case ElementStart(label, attrs) =>

          label.toLowerCase match {
            case NOTIFICATION =>
              val p = captureSnapshotParameters(attrs)
              serial = p._1
              sessionId = p._2

            case SNAPSHOT =>
              val snapshotUri = attrs("uri")
              val snapshotHash = attrs("hash")
          }

          parseNext(attrs, null, elements)

        case ElementEnd(label) =>
          if (NOTIFICATION == label.toLowerCase) elements
          else elements


        case ElementText(newText) =>
          parseNext(lastAttributes, newText, elements)

        case _ => parseNext(lastAttributes, lastText, elements)
      }
    }
    null
  }


  private def doParse[T](xmlSource: BufferedSource, schema: String, parse: StaxParser => T) = {
    val reader: BufferedReader = xmlSource.bufferedReader()
    try {
      // The StaxParser will make make sure that the message is validated against the schema while we are reading it:
      // this way our parsing code can rely on the assumption that the xml is valid
      val parser = StaxParser.createFor(reader, schema)
      parse(parser)
    } finally {
      reader.close()
    }
  }

  private def normalize(s: String) = s.filterNot(Character.isWhitespace)

}
