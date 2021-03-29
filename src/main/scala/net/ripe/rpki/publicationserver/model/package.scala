package net.ripe.rpki.publicationserver

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes

package object model {
  case class ClientId(value: String)

  case class BaseError(code: String, message: String)

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

  case class ReportError(code: String, message: Option[String]) extends ReplyPdu

  object MsgType extends Enumeration {
    type MsgType = Value
    val query = Value("query")
    val reply = Value("reply")
  }

  abstract class Msg extends Formatting {
    def serialize: String

    protected def serialiseReply(serialisePdus: StringBuilder => Unit): String = {
        val sb = new StringBuilder("""<msg type="reply" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">""")
        serialisePdus(sb)
        sb.append("</msg>")
        sb.toString      
    }
    
  }

  case class ErrorMsg(error: BaseError) extends Msg {
    def serialize: String = serialiseReply { sb => 
        sb.append {
            s"""<report_error error_code="${error.code}">
                ${content(error.message)}
            </report_error>"""
        }
    }
  }

  case class ReplyMsg(pdus: Seq[ReplyPdu]) extends Msg {

    def serialize: String = serialiseReply { sb =>       
      pdus.foreach { pdu =>
        val textual = pdu match {
          case PublishR(uri, Some(tag)) => s"""<publish tag="${attr(tag)}" uri="${attr(uri.toASCIIString)}"/>"""
          case PublishR(uri, None) => s"""<publish uri="${attr(uri.toASCIIString)}"/>"""
          case WithdrawR(uri, Some(tag)) => s"""<withdraw tag="${attr(tag)}" uri="${attr(uri.toASCIIString)}"/>"""
          case WithdrawR(uri, None) => s"""<withdraw uri="${attr(uri.toASCIIString)}"/>"""
          case ListR(uri, hash, Some(tag)) => s"""<list tag="${attr(tag)}" uri="${attr(uri.toASCIIString)}" hash="$hash"/>"""
          case ListR(uri, hash, None) => s"""<list uri="${attr(uri.toASCIIString)}" hash="$hash"/>"""
          case ReportError(code, message) =>
            s"""<report_error error_code="$code">
          ${message.map(content).getOrElse("Unspecified error")}
        </report_error>"""
        }
        sb.append(textual).append("\n")
      }      
    }
  }


}
