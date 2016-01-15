package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Props, Actor}
import net.ripe.rpki.publicationserver.messaging.Messages._

object Flusher {
  def props = Props(new Flusher)
}

class Flusher extends Actor {

  override def receive: Receive = {
    case BatchMessage(messages, state) =>
  }
}
