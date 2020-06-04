package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Actor, Props}
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.{AppConfig, Logging, QueryMessage}

import scala.collection.mutable.ListBuffer

object Accumulator {
  def props(conf: AppConfig): Props = Props(new Accumulator(conf))
}

class Accumulator(conf: AppConfig) extends Actor with Logging {

  import context._

  val rrdpFlusher = actorOf(RrdpFlusher.props(conf))
  val rsyncFlusher = actorOf(RsyncFlusher.props(conf))

  private val messages: ListBuffer[QueryMessage] = ListBuffer()
  private var latestState: ObjectStore.State = _

  override def receive: Receive = {
    case vm@ValidatedMessage(m, state) =>
      rsyncFlusher ! vm
      messages += m
      latestState = state
      handleFlushing()
    case ir: InitRepo =>
      rrdpFlusher ! ir
      rsyncFlusher ! ir
  }

  private var scheduled: Boolean = false

  def handleFlushing() = {
    if (!scheduled) {
      system.scheduler.scheduleOnce(conf.snapshotSyncDelay, new Runnable() {
        override def run() = {
          rrdpFlusher ! BatchMessage(messages.toList, latestState)
          messages.clear()
          logger.debug("BatchMessage has been sent")
          scheduled = false
        }
      })
      scheduled = true
    }
  }
}
