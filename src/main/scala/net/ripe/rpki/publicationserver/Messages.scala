package net.ripe.rpki.publicationserver

import scala.annotation.tailrec
import scala.io.Source
import scala.xml._

case class MsgError(code: String, message: String)

case class Base64(s: String)

class QueryPdu()

case class PublishQ(uri: String, base64: Base64) extends QueryPdu

case class WithdrawQ(uri: String) extends QueryPdu

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

object MsgXml {

  val Schema = Source.fromURL(getClass.getResource("/schema.rng")).mkString

  def process(xmlString: String, pduHandler: QueryPdu => ReplyPdu): Either[MsgError, ReplyMsg] = {
    // The StaxParser will make make sure that the message is validated against the schema while we are reading it:
    // this way our parsing code can rely on the assumption that the xml is valid
    val parser = StaxParser.createFor(xmlString, Schema)

    def parse(parser: StaxParser): Either[MsgError, ReplyMsg] = {
      @tailrec
      def parseNext(uri: String, base64: Base64, pduReplies: Seq[ReplyPdu]): Option[ReplyMsg] = {
        if (!parser.hasNext) {
          None
        } else {
          parser.next match {
            case ElementStart(label, attrs) =>
              val newUri = label match {
                case "msg" =>
                  val msgType = attrs("type")

                  // TODO this needs to result in an error message somehow
                  if (!MsgType.query.toString.equals(msgType)) throw new Exception("Messages of type " + msgType + " are not accepted")
                  null

                case "publish" =>
                  attrs("uri")

                case "withdraw" =>
                  attrs("uri")
              }
              parseNext(newUri, null, pduReplies)

            case ElementEnd(label) =>
              val newItem = label match {
                case "msg" =>
                  Left(new ReplyMsg(pduReplies))

                case "publish" =>
                  val pdu = new PublishQ(uri, base64)
                  Right(pduHandler(pdu))

                case "withdraw" =>
                  val pdu = new WithdrawQ(uri)
                  Right(pduHandler(pdu))
              }

              newItem match {
                case Left(msg) => Some(msg)
                case Right(pdu) => parseNext(null, null, pdu +: pduReplies)
              }

            case ElementText(text) =>
              parseNext(uri, Base64.apply(text), pduReplies)

            case _ => parseNext(uri, base64, pduReplies)
          }
        }
      }

      val result = parseNext(null, null, Seq())
      result match {
        case Some(msg) => Right(msg)
        case None => Left(MsgError("42", "The message was empty")) // TODO rethink the message and the code
      }
    }

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

