package net.ripe.rpki.publicationserver

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

case class ListQ(tag: Option[String] = None) extends QueryPdu

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

abstract class Msg {
  def serialize: String

  protected def reply(pdus: => String): String =
    s"""<msg type="reply" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      $pdus
    </msg>"""
}

case class ErrorMsg(error: BaseError) extends Msg {
  def serialize: String = reply {
    s"""<report_error error_code="${error.code}">
      ${error.message}
    </report_error>"""
  }
}

case class ReplyMsg(pdus: Seq[ReplyPdu]) extends Msg {

  def serialize: String = reply {
    val sb = new StringBuilder
    pdus.foreach { pdu =>
      val textual = pdu match {
        case PublishR(uri, Some(tag)) => s"""<publish tag="$tag" uri="$uri"/>"""
        case PublishR(uri, None) => s"""<publish uri="$uri"/>"""
        case WithdrawR(uri, Some(tag)) => s"""<withdraw tag="$tag" uri="$uri"/>"""
        case WithdrawR(uri, None) => s"""<withdraw uri="$uri"/>"""
        case ListR(uri, hash, Some(tag)) => s"""<list tag="$tag" uri="$uri" hash="$hash"/>"""
        case ListR(uri, hash, None) => s"""<list uri="$uri" hash="$hash"/>"""
        case ReportError(code, message) =>
          s"""<report_error error_code="$code">
          ${message.getOrElse("Unspecified error")}
        </report_error>"""
      }
      sb.append(textual).append("\n")
    }
    sb.toString()
  }
}
