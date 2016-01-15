package net.ripe.rpki.publicationserver.messaging

import java.net.URI

import net.ripe.rpki.publicationserver.{Base64, Hash, QueryMessage}

object Messages {

  type State = Map[URI, (Base64, Hash)]

  case class RawMessage(queryMessage: QueryMessage)

  case class ValidatedMessage(queryMessage: QueryMessage, state: State)

  case class BatchMessage(messages: Seq[ValidatedMessage], state: State)
}

