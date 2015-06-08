package net.ripe.rpki.publicationserver

import scala.annotation.tailrec
import scala.io.{BufferedSource, Source}
import scala.xml._
import java.net.URI

object BaseError extends Enumeration {
  type Code = Value
  val NoMsgElement = Value
  val ParseError = Value
  val WrongQueryType = Value
  val HashForInsert = Value
  val NoObjectToUpdate = Value
  val NoObjectForWithdraw = Value
  val NonMatchingHash = Value
  val CouldNotPersist = Value
}

case class BaseError(code: BaseError.Code, message: String)


sealed trait Message

case class QueryMessage(pdus: Seq[QueryPdu]) extends Message

case class ListMessage() extends Message


sealed trait QueryPdu

case class PublishQ(uri: URI, tag: Option[String], hash: Option[String], base64: Base64) extends QueryPdu

case class WithdrawQ(uri: URI, tag: Option[String], hash: String) extends QueryPdu

case class ListQ() extends QueryPdu

class ReplyPdu()

case class PublishR(uri: URI, tag: Option[String]) extends ReplyPdu

case class WithdrawR(uri: URI, tag: Option[String]) extends ReplyPdu

case class ListR(uri: URI, hash: String, tag: Option[String]) extends ReplyPdu

case class ReportError(code: BaseError.Code, message: Option[String]) extends ReplyPdu

object MsgType extends Enumeration {
  type MsgType = Value
  val query = Value("query")
  val reply = Value("reply")
}

class Msg {
  protected def reply(pdus: => NodeSeq): Elem =
    <msg type="reply" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      {pdus}
    </msg>
}

case class ErrorMsg(error: BaseError) extends Msg {
  def serialize = reply {
    <report_error error_code={error.code.toString}>
      {error.message}
    </report_error>
  }
}

case class ReplyMsg(pdus: Seq[ReplyPdu]) extends Msg {

  def serialize = reply {
    pdus.map {
      case PublishR(uri, Some(tag)) => <publish tag={tag} uri={uri.toString}/>
      case PublishR(uri, None) => <publish uri={uri.toString}/>
      case WithdrawR(uri, Some(tag)) => <withdraw tag={tag} uri={uri.toString}/>
      case WithdrawR(uri, None) => <withdraw uri={uri.toString}/>
      case ListR(uri, hash, Some(tag)) => <list tag={tag} uri={uri.toString} hash={hash}/>
      case ListR(uri, hash, None) => <list uri={uri.toString} hash={hash}/>
      case ReportError(code, message) =>
        <report_error error_code={code.toString}>
          {message}
        </report_error>
    }
  }
}

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
