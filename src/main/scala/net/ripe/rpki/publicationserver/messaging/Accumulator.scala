package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Actor, Props}
import net.ripe.rpki.publicationserver.messaging.Messages._

object Accumulator {
  def props: Props = Props(new Accumulator)
}

class Accumulator extends Actor {

  override def receive: Receive = {
    case ValidatedMessage(m, state) =>
  }

}
