package net.ripe.rpki.publicationserver

import java.net.URI

import net.ripe.rpki.publicationserver.parsing._

import scala.annotation.tailrec
import scala.io.Source
import scala.xml._

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

case class ListQ() extends QueryPdu

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
  def serialize: Elem

  protected def reply(pdus: => NodeSeq): Elem =
    <msg type="reply" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      {pdus}
    </msg>
}

case class ErrorMsg(error: BaseError) extends Msg {
  def serialize: Elem = reply {
    <report_error error_code={error.code.toString}>
      {error.message}
    </report_error>
  }
}

case class ReplyMsg(pdus: Seq[ReplyPdu]) extends Msg {

  def serialize: Elem = reply {
    pdus.map {
      case PublishR(uri, Some(tag)) => <publish tag={tag} uri={uri.toString}/>
      case PublishR(uri, None) => <publish uri={uri.toString}/>
      case WithdrawR(uri, Some(tag)) => <withdraw tag={tag} uri={uri.toString}/>
      case WithdrawR(uri, None) => <withdraw uri={uri.toString}/>
      case ListR(uri, hash, Some(tag)) => <list tag={tag} uri={uri.toString} hash={hash}/>
      case ListR(uri, hash, None) => <list uri={uri.toString} hash={hash}/>
      case ReportError(code, message) =>
        <report_error error_code={code.toString}>
          {message}
        </report_error>
    }
  }
}
