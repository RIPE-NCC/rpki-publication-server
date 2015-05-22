package net.ripe.rpki.publicationserver

import java.util.concurrent.atomic.AtomicReference

import com.google.common.io.BaseEncoding

import scala.xml.{Elem, Node}

class SnapshotState(val sessionId: SessionId, val serial: BigInt, val pdus: SnapshotState.SnapshotMap) {

  def apply(queries: Seq[QueryPdu]): Either[MsgError, SnapshotState] = {

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

    newPdus.right.map(new SnapshotState(sessionId, serial + 1, _))
  }

  def serialize = snapshotXml (
    sessionId,
    serial,
    pdus.map { e =>
      val (uri, (base64, hash)) = e
      <publish uri={uri} hash={hash.hash}>{base64.value}</publish>
    }
  )

  private def snapshotXml(sessionId: SessionId, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <snapshot xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.id} serial={serial.toString()}>
      {pdus}
    </snapshot>
}

object SnapshotState extends Hashing {
  private val base64 = BaseEncoding.base64()

  type SnapshotMap = Map[String, (Base64, Hash)]

  private val state = new AtomicReference[SnapshotState]()

  def get = state.get()

  def transform(t: SnapshotState => SnapshotState): SnapshotState = {
    var currentState: SnapshotState = null
    var newState: SnapshotState = null
    do {
      currentState = state.get
      newState = t(currentState)
    }
    while (!state.compareAndSet(currentState, newState))
    newState
  }

  def hash(b64: Base64): Hash = {
    val Base64(b64String) = b64
    val bytes = base64.decode(b64String)
    hash(bytes)
  }
}


