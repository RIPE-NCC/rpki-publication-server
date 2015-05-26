package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.google.common.io.BaseEncoding

import scala.xml.{Elem, Node}

case class SnapshotState(sessionId: UUID, serial: BigInt, pdus: SnapshotState.SnapshotMap) {

  def apply(queries: Seq[QueryPdu]): (Seq[ReplyPdu], Option[SnapshotState]) = {

    var newPdus = pdus
    val replies = queries.map({
        case PublishQ(uri, tag, None, base64) =>
          newPdus.get(uri) match {
            case Some(_) => ReportError(MsgError.HashForInsert, Some(s"Tried to insert existing object [$uri]."))
            case None    =>
              newPdus += (uri ->(base64, SnapshotState.hash(base64)))
              PublishR(uri, tag)
          }

        case PublishQ(uri, tag, Some(qHash), base64) =>
          newPdus.get(uri) match {
            case Some((_, h)) =>
              if (h == Hash(qHash)) {
                newPdus += (uri ->(base64, SnapshotState.hash(base64)))
                PublishR(uri, tag)
              } else
                ReportError(MsgError.NonMatchingHash, Some(s"Cannot republish the object [$uri], hash doesn't match"))

            case None =>
              ReportError(MsgError.NoObjectToUpdate, Some(s"No object [$uri] has been found."))
          }

        case WithdrawQ(uri, tag, qHash) =>
          newPdus.get(uri) match {
            case Some((_, h)) =>
              if (h == Hash(qHash)) {
                newPdus -= uri
                WithdrawR(uri, tag)
              } else
                ReportError(MsgError.NonMatchingHash, Some(s"Cannot withdraw the object [$uri], hash doesn't match."))

            case None =>
              ReportError(MsgError.NoObjectForWithdraw, Some(s"No object [$uri] found."))
          }
      }
    )

    if (replies.exists(r => r.isInstanceOf[ReportError]))
      (replies, None)
    else
      (replies, Some(SnapshotState(sessionId, serial + 1, newPdus)))
  }

  def serialize = snapshotXml (
    sessionId,
    serial,
    pdus.map { e =>
      val (uri, (base64, hash)) = e
      <publish uri={uri.toString} hash={hash.hash}>{base64.value}</publish>
    }
  )

  private def snapshotXml(sessionId: UUID, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <snapshot xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.toString} serial={serial.toString()}>
      {pdus}
    </snapshot>
}

object SnapshotState extends Hashing {
  private val base64 = BaseEncoding.base64()

  type SnapshotMap = Map[URI, (Base64, Hash)]

  private val state = new AtomicReference[SnapshotState](emptySnapshot)

  def emptySnapshot = new SnapshotState(UUID.randomUUID(), BigInt(1), Map.empty)

  def get = state.get()

  def initializeWith(initState: SnapshotState) = state.set(initState)

  def updateWith(queries: Seq[QueryPdu]): Seq[ReplyPdu] = {
    // TODO replace these with real values
    val sessionId = UUID.randomUUID()
    val uri = "test-uri"

    val currentState = state.get
    val (replies, newState) = currentState(queries)
    if (newState.isDefined) {
        while (!state.compareAndSet(currentState, newState.get))
        NotificationState.update(sessionId, uri, newState.get)
    }
    replies
  }

  def hash(b64: Base64): Hash = {
    val Base64(b64String) = b64
    val bytes = base64.decode(b64String)
    hash(bytes)
  }
}


