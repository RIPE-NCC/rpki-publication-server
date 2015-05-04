package com.example

import scala.annotation.tailrec
import scala.io.Source
import scala.xml.pull._

case class Base64(s: String)

object MsgType extends Enumeration {
  type MsgType = Value
  val query = Value("query")
  val reply = Value("reply")
}

case class Msg(msgType: MsgType.MsgType, pdus: Seq[Pdu])

class Pdu()

case class Publish(uri: String, base64: Base64) extends Pdu

case class Withdraw(uri: String) extends Pdu

case class ReportError(code: String, message: Option[String]) extends Pdu

object MsgXml {

  def parse(xmlSring: String): Msg = {
    // TODO Replace it with a stream
    val xml = new XMLEventReader(Source.fromString(xmlSring))

    def parse(xml: XMLEventReader) {
      @tailrec
      def loop(currNode: List[String]) {
        if (xml.hasNext) {
          xml.next match {
            case EvElemStart(_, label, _, _) =>
              println("Start element: " + label)
              loop(label :: currNode)
            case EvElemEnd(_, label) =>
              println("End element: " + label)
              loop(currNode.tail)
            case EvText(text) =>
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

  def serialize(msg: Msg): String = {
    ""
  }

}

