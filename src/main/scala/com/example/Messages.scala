package com.example

import scala.annotation.tailrec
import scala.io.Source
import scala.xml.pull._

case class MsgError(code: String, message: String)

case class Base64(s: String)

case class Msg[P](msgType: MsgType.MsgType, pdus: Seq[P])

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

  type QueryMsg = Msg[QueryPdu]
  type ReplyMsg = Msg[ReplyPdu]
}


object MsgXml {

  import MsgType.QueryMsg

  def parseStream(xmlString: String): QueryMsg = {
    // TODO Replace it with a stream
    val xml = new XMLEventReader(Source.fromString(xmlString))

    def parse(xml: XMLEventReader) {
      @tailrec
      def loop(currNode: List[String]) {
        if (xml.hasNext) {
          xml.next() match {
            case EvElemStart(_, label, attrs, _) =>
              println("Start element: " + label)
              label match {
                case "msg" => attrs.get("type")
                case "publish" =>
                case "withdraw" =>
              }

              loop(label :: currNode)
            case EvElemEnd(_, label) =>
              println("End element: " + label)
              label match {
                case "msg" =>
                case "publish" =>
                case "withdraw" =>
              }
              loop(currNode.tail)
            case EvText(text) =>
              println("Text: " + text)
              loop(currNode)
            case _ => loop(currNode)
          }
        }
      }
      loop(List.empty)
    }

    parse(xml)

    Msg(MsgType.query, Seq())
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

      Right(Msg(MsgType.query, publishes ++ withdraws))
    }
  }

  def serialize(msg: MsgType.ReplyMsg): String = {
    val xml = <msg
    type="reply"
    version="3"
    xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      {msg.pdus.map {
        case PublishR(uri) => <publish uri={uri}/>
        case WithdrawR(uri) => <withdraw uri={uri}/>
      }}
    </msg>

    xml.toString()
  }

}

