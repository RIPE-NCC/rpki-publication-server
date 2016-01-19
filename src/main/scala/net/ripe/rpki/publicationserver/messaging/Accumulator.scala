package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.{AppConfig, Logging, QueryMessage}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

object Accumulator {
  def props(conf: AppConfig): Props = Props(new Accumulator(conf))
}

class Accumulator(conf: AppConfig) extends Actor with Logging {

  import context._

  private var flusher: ActorRef = _

  private val messages: ListBuffer[QueryMessage] = ListBuffer()
  private var scheduled: Boolean = false
  private var latestState: ObjectStore.State = _

  override def preStart() = {
    flusher = context.actorOf(FSFlusher.props(conf))
  }

  override def receive: Receive = {
    case ValidatedMessage(m, state) =>
      messages += m
      latestState = state
      handleFlushing()
    case ir : InitRepo =>
      flusher ! ir
  }

  def handleFlushing() = {
    if (!scheduled) {
      system.scheduler.scheduleOnce(conf.snapshotSyncDelay, new Runnable() {
        override def run() = {
          flusher ! BatchMessage(messages.toSeq, latestState)
          logger.debug("BatchMessage has been sent")
          scheduled = false
        }
      })
      scheduled = true
    }
  }
}
