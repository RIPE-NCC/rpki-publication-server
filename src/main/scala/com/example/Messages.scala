package com.example

import java.io.StringReader
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants}

import org.codehaus.stax2.{XMLInputFactory2, XMLStreamReader2}

import scala.annotation.tailrec

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

trait StaxEvent {
}

object StaxEvent {
  def read(reader: XMLStreamReader2): StaxEvent = {
    reader.next() match {
      case XMLStreamConstants.START_ELEMENT => {
        val label = reader.getName.toString
        val nrAttrs = reader.getAttributeCount
        val attrs = (0 until nrAttrs).map(i => {
          val attrName = reader.getAttributeLocalName(i)
          val value = reader.getAttributeValue(i)
          attrName -> value
        }).toMap
        new ElementStart(label, attrs)
      }
      case XMLStreamConstants.END_ELEMENT => {
        val label = reader.getName.toString
        new ElementEnd(label)
      }
      case XMLStreamConstants.CHARACTERS => {
        val text = reader.getText()
        new ElementText(text)
      }
    }
  }
}

case class ElementStart(label: String, attrs: Map[String, String]) extends StaxEvent {
}

case class ElementEnd(label: String) extends StaxEvent {
}

case class ElementText(text: String) extends StaxEvent {
}

object MsgXml {

  import MsgType.QueryMsg

  def parseStream(xmlString: String): QueryMsg = {
    val xmlif: XMLInputFactory2 = XMLInputFactory.newInstance() match {
      case x: XMLInputFactory2 => x
      case _ => throw new ClassCastException
    }
    val reader: XMLStreamReader2 = xmlif.createXMLStreamReader(new StringReader(xmlString)) match {
      case x: XMLStreamReader2 => x
      case _ => throw new ClassCastException
    }

    def parse(reader: XMLStreamReader2) {
      @tailrec
      def loop(currNode: List[String]) {
        if (reader.hasNext) {

          StaxEvent.read(reader) match {
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

    parse(reader)

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

