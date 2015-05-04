package com.example

case class Base64(s: String)

class MsgType extends Enumeration {
  type MsgType = Value
  val query = Value("query")
  val reply = Value("reply")
}

case class Msg(msgType: MsgType, pdus: Seq[Pdu])

case class Pdu()

case class Publish(uri: String, base64: Base64) extends Pdu

case class Withdraw(uri: String) extends Pdu

case class ReportError(code: String, message: Option[String]) extends Pdu



