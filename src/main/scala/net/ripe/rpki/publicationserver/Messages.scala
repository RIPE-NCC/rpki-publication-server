package net.ripe.rpki.publicationserver

import scala.annotation.tailrec
import scala.io.Source
import scala.xml._

case class MsgError(code: String, message: String)

case class Base64(s: String)

class QueryPdu()

case class PublishQ(uri: String, hash: Option[String], base64: Base64) extends QueryPdu

case class WithdrawQ(uri: String, hash: String) extends QueryPdu

class ReplyPdu()

case class PublishR(uri: String) extends ReplyPdu

case class WithdrawR(uri: String) extends ReplyPdu

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

    def parseElementStart(label: String, attrs: Map[String, String]): Either[MsgError, (String, Option[String])] = {
      label.toLowerCase match {
        case "msg" =>
          val msgType = attrs("type")

          if (!MsgType.query.toString.equalsIgnoreCase(msgType))
            Left(MsgError("Wrong query type", "Messages of type " + msgType + " are not accepted"))
          else
            Right(null, None)

        case "publish" =>
          Right(attrs("uri"), attrs.get("hash"))

        case "withdraw" =>
          Right(attrs("uri"), attrs.get("hash"))
      }
    }

    def parse(parser: StaxParser): Either[MsgError, ReplyMsg] = {
      @tailrec
      def parseNext(uri: String, hash: Option[String], base64: Base64, pduReplies: Seq[ReplyPdu]): Either[MsgError, ReplyMsg] = {
        if (!parser.hasNext) {
          Left(MsgError("No msg element", "The request does not contain a msg element"))
        } else {
          parser.next match {
            case ElementStart(label, attrs) =>
              val newItem = parseElementStart(label, attrs)

              newItem match {
                case Right((newUri, newHash)) => parseNext(newUri, newHash, null, pduReplies)
                case Left(errorMsg) => Left(errorMsg)
              }

            case ElementEnd(label) =>
              val newItem = label.toLowerCase match {
                case "msg" =>
                  Left(new ReplyMsg(pduReplies))

                case "publish" =>
                  val pdu = new PublishQ(uri, hash, base64)
                  Right(pduHandler(pdu))

                case "withdraw" =>
                  val pdu = new WithdrawQ(uri, hash.get)
                  Right(pduHandler(pdu))
              }

              newItem match {
                case Left(msg) => Right(msg)
                case Right(pdu) => parseNext(null, null, null, pdu +: pduReplies)
              }

            case ElementText(text) =>
              parseNext(uri, hash, Base64.apply(text), pduReplies)

            case _ => parseNext(uri, hash, base64, pduReplies)
          }
        }
      }

      parseNext(null, null, null, Seq())
    }

    // The StaxParser will make make sure that the message is validated against the schema while we are reading it:
    // this way our parsing code can rely on the assumption that the xml is valid
    val parser = StaxParser.createFor(xmlString, Schema)

    parse(parser)
  }

  def serialize(msg: ReplyMsg) = reply {
    msg.pdus.map {
      case PublishR(uri) => <publish uri={uri}/>
      case WithdrawR(uri) => <withdraw uri={uri}/>
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