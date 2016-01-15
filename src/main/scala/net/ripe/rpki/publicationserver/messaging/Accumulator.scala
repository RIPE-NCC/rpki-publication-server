package net.ripe.rpki.publicationserver.messaging

import akka.actor.Actor
import net.ripe.rpki.publicationserver.messaging.Messages._

class Accumulator extends Actor {

  override def receive: Receive = {
    case ValidatedMessage(m, state) =>
  }

}
