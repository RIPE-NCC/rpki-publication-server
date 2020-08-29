package net.ripe.rpki.publicationserver.parsing

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver._

import scala.annotation.tailrec
import scala.io.Source


class PublicationMessageParser extends MessageParser[Message] {

  override val Schema = Source.fromURL(getClass.getResource("/rpki-publication-schema.rng")).mkString

  def trim(s: String): String = s.filterNot(_.isWhitespace)

  override protected def parse(parser: StaxParser): Either[BaseError, Message] = {
    @tailrec
    def parseNext(lastAttributes: Map[String, String], lastText: String, pdus: Seq[QueryPdu]): Either[BaseError, Message] = {
      if (!parser.hasNext) {
        Left(BaseError("xml_error", "The request does not contain a complete msg element"))
      } else {
        parser.next match {

          case ElementStart(label, attrs) =>
            if (label.equalsIgnoreCase("msg") && !MsgType.query.toString.equalsIgnoreCase(attrs("type")))
              Left(BaseError("xml_error", "Messages of type " + attrs("type") + " are not accepted"))
            else
              parseNext(attrs, "", pdus)

          case ElementEnd(label) =>
            val msgOrPdu = label.toLowerCase match {
              case "msg" =>
                Left(QueryMessage(pdus))

              case "publish" =>
                val trimmedText = trim(lastText)
                try {
                  val bytes = Bytes.fromBase64(Base64(trimmedText))
                  val pdu = PublishQ(uri = new URI(lastAttributes("uri")), tag = lastAttributes.get("tag"), hash = lastAttributes.get("hash"), bytes = bytes)
                  Right(pdu)
                } catch {
                  case _: Exception =>
                    Left(ErrorMessage(BaseError("xml_error", s"Invalid base64 representation for the object ${lastAttributes("uri")}")))
                }

              case "withdraw" =>
                val pdu = WithdrawQ(uri = new URI(lastAttributes("uri")), tag = lastAttributes.get("tag"), hash = lastAttributes("hash"))
                Right(pdu)

              case "list" => // TODO ??? implement tags for list query
                Left(ListMessage())
            }

            msgOrPdu match {
              case Left(msg) => Right(msg)
              case Right(pdu) => parseNext(null, null, pdu +: pdus)
            }

          case ElementText(newText) =>
            parseNext(lastAttributes, lastText + newText, pdus)

          case _ => parseNext(lastAttributes, lastText, pdus)
        }
      }
    }

    parseNext(null, null, Seq())
  }

}

