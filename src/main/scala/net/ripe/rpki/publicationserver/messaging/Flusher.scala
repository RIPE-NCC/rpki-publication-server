package net.ripe.rpki.publicationserver.messaging

import akka.actor.Actor
import net.ripe.rpki.publicationserver.messaging.Messages._

class Flusher extends Actor {

  override def receive: Receive = {
    case BatchMessage(messages, state) =>
  }
}
