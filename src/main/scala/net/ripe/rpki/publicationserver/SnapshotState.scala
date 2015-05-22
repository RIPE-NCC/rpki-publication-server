package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.google.common.io.BaseEncoding

import scala.xml.{Elem, Node}

case class SnapshotState(sessionId: UUID, serial: BigInt, pdus: SnapshotState.SnapshotMap) {

  def apply(queries: Seq[QueryPdu]): (Seq[ReplyPdu], Option[SnapshotState]) = {

    val newPdus = queries.foldLeft[Either[MsgError, SnapshotState.SnapshotMap]](Right(pdus)) { (pduMap, query) =>
      pduMap.right.flatMap { m =>
        query match {
          case PublishQ(uri, _, None, base64) =>
            m.get(uri) match {
              case Some(_) => Left(MsgError(MsgError.HashForInsert, s"Tried to insert existing object [$uri]."))
              case None    => Right(m + (uri ->(base64, SnapshotState.hash(base64))))
            }

          case PublishQ(uri, _, Some(qHash), base64) =>
            m.get(uri) match {
              case Some((_, h)) =>
                if (h == Hash(qHash))
                  Right(m + (uri ->(base64, SnapshotState.hash(base64))))
                else
                  Left(MsgError(MsgError.NonMatchingHash, s"Cannot republish the object [$uri], hash doesn't match"))

              case None =>
                Left(MsgError(MsgError.NoObjectToUpdate, s"No object [$uri] has been found."))
            }

          case WithdrawQ(uri, _, qHash) =>
            m.get(uri) match {
              case Some((_, h)) =>
                if (h == Hash(qHash))
                  Right(m - uri)
                else
                  Left(MsgError(MsgError.NonMatchingHash, s"Cannot withdraw the object [$uri], hash doesn't match."))

              case None =>
                Left(MsgError(MsgError.NoObjectForWithdraw, s"No object [$uri] found."))
            }
        }
      }
    }

    newPdus.right.map(SnapshotState(sessionId, serial + 1, _))
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
//
//  def transform(t: SnapshotState => Either[MsgError, SnapshotState]): Either[MsgError, SnapshotState] = {
//    var currentState: SnapshotState = null
//    var newState: SnapshotState = null
//    do {
//      currentState = state.get
//      val result: Either[MsgError, SnapshotState] = t(currentState)
//      if (result.isLeft)
//        return result
//      else
//        newState = result.right.get
//    }
//    while (!state.compareAndSet(currentState, newState))
//    Right(newState)
//  }

  def hash(b64: Base64): Hash = {
    val Base64(b64String) = b64
    val bytes = base64.decode(b64String)
    hash(bytes)
  }
}


