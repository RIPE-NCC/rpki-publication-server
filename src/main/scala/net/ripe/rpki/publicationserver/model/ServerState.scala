package net.ripe.rpki.publicationserver.model

import java.util.UUID

case class ServerState(sessionId: UUID, serialNumber: Long) {
  def next = ServerState(sessionId, serialNumber + 1)
}
