package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

case class SessionId(id: String)

case class Hash(h: String)

class SnapshotState(sessionId: SessionId, serial: BigInt) {
  val snapshot = Map[Hash, (URI, Base64)]()

  def apply(queries: Seq[QueryPdu]): SnapshotState = {
    this
  }
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


