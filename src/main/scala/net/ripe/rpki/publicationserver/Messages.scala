package net.ripe.rpki.publicationserver

import scala.annotation.tailrec
import scala.io.Source
import scala.xml._

case class MsgError(code: String, message: String)

case class Base64(s: String)

class QueryPdu()

case class PublishQ(uri: String, tag: Option[String], hash: Option[String], base64: Base64) extends QueryPdu

case class WithdrawQ(uri: String, tag: Option[String], hash: String) extends QueryPdu

class ReplyPdu()

case class PublishR(uri: String, tag: Option[String]) extends ReplyPdu

case class WithdrawR(uri: String, tag: Option[String]) extends ReplyPdu

case class ReportError(code: String, message: Option[String]) extends ReplyPdu

object MsgType extends Enumeration {
  type MsgType = Value
  val query = Value("query")
  val reply = Value("reply")
}

class ReplyMsg(val pdus: Seq[ReplyPdu])

class MsgParser {

  val Schema = Source.fromURL(getClass.getResource("/schema.rng")).mkString

  def process(xmlString: String, pduHandler: QueryPdu => ReplyPdu): Either[MsgError, ReplyMsg] = {

    def parse(parser: StaxParser): Either[MsgError, ReplyMsg] = {
      @tailrec
      def parseNext(lastAttributes: Map[String, String], lastText: String, pduReplies: Seq[ReplyPdu]): Either[MsgError, ReplyMsg] = {
        if (!parser.hasNext) {
          Left(MsgError("No msg element", "The request does not contain a complete msg element"))
        } else {
          parser.next match {
            case ElementStart(label, attrs) =>
              if (label.equalsIgnoreCase("msg") && !MsgType.query.toString.equalsIgnoreCase(attrs("type")))
                Left(MsgError("Wrong query type", "Messages of type " + attrs("type") + " are not accepted"))
              else
                parseNext(attrs, null, pduReplies)

            case ElementEnd(label) =>
              val msgOrPdu = label.toLowerCase match {
                case "msg" =>
                  Left(new ReplyMsg(pduReplies))

                case "publish" =>
                  val pdu = new PublishQ(uri = lastAttributes("uri"), tag = lastAttributes.get("tag"), hash = lastAttributes.get("hash"), base64 = Base64.apply(lastText))
                  Right(pduHandler(pdu))

                case "withdraw" =>
                  val pdu = new WithdrawQ(uri = lastAttributes("uri"), tag = lastAttributes.get("tag"), hash = lastAttributes("hash"))
                  Right(pduHandler(pdu))
              }

              msgOrPdu match {
                case Left(msg) => Right(msg)
                case Right(pdu) => parseNext(null, null, pdu +: pduReplies)
              }

            case ElementText(newText) =>
              parseNext(lastAttributes, newText, pduReplies)

            case _ => parseNext(lastAttributes, lastText, pduReplies)
          }
        }
      }

      parseNext(null, null, Seq())
    }

    // The StaxParser will make make sure that the message is validated against the schema while we are reading it:
    // this way our parsing code can rely on the assumption that the xml is valid
    val parser = StaxParser.createFor(xmlString, Schema)

    parse(parser)
  }

  def serialize(msg: ReplyMsg) = reply {
    msg.pdus.map {
      case PublishR(uri, Some(tag)) => <publish tag={tag} uri={uri}/>
      case PublishR(uri, None) => <publish uri={uri}/>
      case WithdrawR(uri, Some(tag)) => <withdraw tag={tag} uri={uri}/>
      case WithdrawR(uri, None) => <withdraw uri={uri}/>
      case ReportError(code, message) =>
        <report_error error_code={code}>
          {message}
        </report_error>
    }
  }

  def serialize(msgError: MsgError) = reply {
    <report_error error_code={msgError.code}>
      {msgError.message}
    </report_error>
  }

  private def reply(pdus: => NodeSeq): Elem =
    <msg type="reply" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      {pdus}
    </msg>

}