package net.ripe.rpki.publicationserver

import scala.annotation.tailrec
import scala.xml._

case class MsgError(code: String, message: String)

case class Base64(s: String)

class Msg[P](val msgType: MsgType.MsgType, pdus: Seq[P])

class QueryPdu()

class ReplyPdu()

case class PublishQ(uri: String, base64: Base64) extends QueryPdu

case class PublishR(uri: String) extends ReplyPdu

case class WithdrawQ(uri: String) extends QueryPdu

case class WithdrawR(uri: String) extends ReplyPdu

case class ReportError(code: String, message: Option[String]) extends ReplyPdu

object MsgType extends Enumeration {
  type MsgType = Value
  val query = Value("query")
  val reply = Value("reply")
}

class QueryMsg(val pdus: Seq[QueryPdu]) extends Msg[QueryPdu](MsgType.query, pdus)
class ReplyMsg(val pdus: Seq[ReplyPdu]) extends Msg[ReplyPdu](MsgType.reply, pdus)


object MsgXml {

  def parseStream(xmlString: String): Either[MsgError, QueryMsg] = {
    val parser = StaxParser.createFor(xmlString, null) // TODO add rng

    def parse(parser: StaxParser) {
      @tailrec
      def loop(currNode: List[String]) {
        if (parser.hasNext) {

          parser.next match {
            case ElementStart(label, attrs) =>
              println("Start element: " + label)
              label match {
                case "msg" => attrs("type")
                case "publish" =>
                case "withdraw" =>
              }

              loop(label :: currNode)
            case ElementEnd(label) =>
              println("End element: " + label)
              label match {
                case "msg" =>
                case "publish" =>
                case "withdraw" =>
              }
              loop(currNode.tail)
            case ElementText(text) =>
              println("Text: " + text)
              loop(currNode)
            case _ => loop(currNode)
          }
        }
      }
      loop(List.empty)
    }

    parse(parser)

    Right(new QueryMsg(Seq()))
  }

  def parse(xmlString: String): Either[MsgError, QueryMsg] = {
    val xml = try
      Right(scala.xml.XML.loadString(xmlString))
    catch {
      case e: Exception =>
        e.printStackTrace()
        Left(MsgError(e.getMessage, "Could not parse XML"))
    }

    xml.right.flatMap { xml =>
      val publishes = (xml \ "publish").map(x => PublishQ((x \ "@uri").text, Base64(x.text)))
      val withdraws = (xml \ "withdraw").map(x => WithdrawQ((x \ "@uri").text))

      Right(new QueryMsg(publishes ++ withdraws))
    }
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

