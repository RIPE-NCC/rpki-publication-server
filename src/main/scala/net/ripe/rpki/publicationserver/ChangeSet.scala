package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.model.{ServerState, Delta}

case class ChangeSet(deltas: Map[Long, Delta]) extends Hashing {

  def next(newServerState: ServerState, queries: Seq[QueryPdu]): ChangeSet = {
    val ServerState(sessionId, newSerial) = newServerState
    val newDeltas = deltas + (newSerial -> Delta(sessionId, newSerial, queries))
    ChangeSet(newDeltas)
  }

  def latestDelta(serial: Long) = deltas.get(serial)
}


