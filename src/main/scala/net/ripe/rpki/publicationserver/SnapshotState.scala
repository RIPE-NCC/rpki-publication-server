package net.ripe.rpki.publicationserver

import java.util.concurrent.atomic.AtomicReference

case class SessionId(id: String)

case class Hash(h: String)

class SnapshotState(sessionId: SessionId, serial: BigInt, pdus: Map[String, (Base64, Hash)]) {
  def apply(queries: Seq[QueryPdu]): Either[MsgError, SnapshotState] = {
    val newPdus = queries.foldLeft[Either[MsgError, Map[String, (Base64, Hash)]]](Right(pdus)) { (pduMap, query) =>
      pduMap.right.flatMap { m =>
        query match {
          case PublishQ(uri, _, None, base64) =>
            m.get(uri) match {
              case Some((_, h)) =>
                Left(MsgError(MsgError.HashForInsert, s"Redundant hash provided for inserting the object with uri=$uri"))
              case None =>
                Right(m + (uri ->(base64, hash(base64))))
            }

          case PublishQ(uri, _, Some(qHash), base64) =>
            m.get(uri) match {
              case Some((_, h)) =>
                if (h == Hash(qHash))
                  Right(m - uri)
                else
                  Left(MsgError(MsgError.NonMatchingHash, s"Cannot republish the object with uri=$uri, hash doesn't match"))

              case None =>
                Left(MsgError(MsgError.NoHashForUpdate, s"uri=$uri"))
            }

          case WithdrawQ(uri, _, qHash) =>
            m.get(uri) match {
              case Some((_, h)) =>
                if (h == Hash(qHash))
                  Right(m - uri)
                else
                  Left(MsgError(MsgError.NonMatchingHash, s"Cannot withdraw the object with uri=$uri, hash doesn't match"))

              case None =>
                Left(MsgError(MsgError.NoHashForWithdraw, s"No hash provided for withdrawing the object $uri"))
            }
        }
      }
    }

    newPdus.right.map(new SnapshotState(sessionId, serial + 1, _))
  }

  // TODO Implement hashing
  def hash(b64: Base64) = Hash("hash!")
}

object SnapshotState {
  private val state: AtomicReference[SnapshotState] = new AtomicReference[SnapshotState]()

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

}


