package net.ripe.rpki.publicationserver.messaging

import net.ripe.rpki.publicationserver.QueryMessage
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore

object Messages {

  case class RawMessage(message: net.ripe.rpki.publicationserver.Message, clientId: ClientId)

  case class ValidatedMessage(queryMessage: QueryMessage, state: ObjectStore.State)

  case class BatchMessage(messages: Seq[QueryMessage], state: ObjectStore.State)

}

