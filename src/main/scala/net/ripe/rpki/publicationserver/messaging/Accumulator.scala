package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.{Config, Logging, QueryMessage}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

object Accumulator {
  def props(): Props = Props(new Accumulator())
}

class Accumulator() extends Actor with Config with Logging {

  import context._

  private var flusher: ActorRef = _

  private val messages: ListBuffer[QueryMessage] = ListBuffer()
  private var scheduled: Boolean = false
  private var latestState: ObjectStore.State = _

  override def preStart() = {
    flusher = context.actorOf(FSFlusher.props)
  }

  def flushInterval: FiniteDuration = {
    val i = conf.unpublishedFileRetainPeriod / 10
    if (i < 1.second) 1.second else i
  }

  override def receive: Receive = {
    case ValidatedMessage(m, state) =>
      messages += m
      latestState = state
      handleFlushing()
  }

  def handleFlushing() = {
    if (!scheduled) {
      system.scheduler.scheduleOnce(flushInterval, new Runnable() {
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
