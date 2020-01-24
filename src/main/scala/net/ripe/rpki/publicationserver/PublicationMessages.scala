package net.ripe.rpki.publicationserver

import java.net.URI

import com.google.common.xml.XmlEscapers
import net.ripe.rpki.publicationserver.Binaries.Bytes

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
  val InvalidBase64 = Value
}

case class BaseError(code: BaseError.Code, message: String)


sealed trait Message

case class QueryMessage(pdus: Seq[QueryPdu]) extends Message

case class ListMessage() extends Message

case class ErrorMessage(baseError: BaseError) extends Message


sealed trait QueryPdu

case class PublishQ(uri: URI, tag: Option[String], hash: Option[String], bytes: Bytes) extends QueryPdu

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

  private val attrEscaper = XmlEscapers.xmlAttributeEscaper()
  private val contentEscaper = XmlEscapers.xmlContentEscaper()

  protected def attr(s: String): String = attrEscaper.escape(s)
  protected def content(s: String): String = contentEscaper.escape(s)

  protected def reply(pdus: => String): String =
    s"""<msg type="reply" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      $pdus
    </msg>"""
}

case class ErrorMsg(error: BaseError) extends Msg {
  def serialize: String = reply {
    s"""<report_error error_code="${error.code}">
      ${content(error.message)}
    </report_error>"""
  }
}

case class ReplyMsg(pdus: Seq[ReplyPdu]) extends Msg {

  def serialize: String = reply {
    val sb = new StringBuilder
    pdus.foreach { pdu =>
      val textual = pdu match {
        case PublishR(uri, Some(tag)) => s"""<publish tag="${attr(tag)}" uri="${uri.toASCIIString}"/>"""
        case PublishR(uri, None) => s"""<publish uri="${uri.toASCIIString}"/>"""
        case WithdrawR(uri, Some(tag)) => s"""<withdraw tag="${attr(tag)}" uri="${uri.toASCIIString}"/>"""
        case WithdrawR(uri, None) => s"""<withdraw uri="${uri.toASCIIString}"/>"""
        case ListR(uri, hash, Some(tag)) => s"""<list tag="${attr(tag)}" uri="${uri.toASCIIString}" hash="$hash"/>"""
        case ListR(uri, hash, None) => s"""<list uri="${uri.toASCIIString}" hash="$hash"/>"""
        case ReportError(code, message) =>
          s"""<report_error error_code="$code">
          ${message.map(content).getOrElse("Unspecified error")}
        </report_error>"""
      }
      sb.append(textual).append("\n")
    }
    sb.toString()
  }
}
