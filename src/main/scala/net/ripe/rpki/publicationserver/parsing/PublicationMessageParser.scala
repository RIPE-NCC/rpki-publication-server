package net.ripe.rpki.publicationserver.parsing

import java.net.URI

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
        Left(BaseError(BaseError.NoMsgElement, "The request does not contain a complete msg element"))
      } else {
        parser.next match {

          case ElementStart(label, attrs) =>
            if (label.equalsIgnoreCase("msg") && !MsgType.query.toString.equalsIgnoreCase(attrs("type")))
              Left(BaseError(BaseError.WrongQueryType, "Messages of type " + attrs("type") + " are not accepted"))
            else
              parseNext(attrs, "", pdus)

          case ElementEnd(label) =>
            val msgOrPdu = label.toLowerCase match {
              case "msg" =>
                Left(QueryMessage(pdus))

              case "publish" =>
                val trimmedText = trim(lastText)
                val pdu = new PublishQ(uri = new URI(lastAttributes("uri")), tag = lastAttributes.get("tag"), hash = lastAttributes.get("hash"), base64 = Base64(trimmedText))
                Right(pdu)

              case "withdraw" =>
                val pdu = new WithdrawQ(uri = new URI(lastAttributes("uri")), tag = lastAttributes.get("tag"), hash = lastAttributes("hash"))
                Right(pdu)

              case "list" =>
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

