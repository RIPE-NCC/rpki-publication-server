package net.ripe.rpki.publicationserver.messaging

import java.nio.file.attribute.FileTime
import java.util.UUID

import net.ripe.rpki.publicationserver.QueryMessage
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore

object Messages {

  case class RawMessage(message: net.ripe.rpki.publicationserver.Message, clientId: ClientId)

  case class ValidatedMessage(queryMessage: QueryMessage, state: ObjectStore.State)

  case class BatchMessage(messages: Seq[QueryMessage], state: ObjectStore.State)

  case class InitRepo(state: ObjectStore.State)

  case class CleanUpSnapshot(timestamp: FileTime, serial: Long)

  case class CleanUpDeltas(sessionId: UUID, serials: Iterable[Long])

  case class CleanUpRepo(sessionId: UUID)
}

