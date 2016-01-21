package net.ripe.rpki.publicationserver.model

import java.util.UUID

case class ServerState(sessionId: UUID, serialNumber: Long)
