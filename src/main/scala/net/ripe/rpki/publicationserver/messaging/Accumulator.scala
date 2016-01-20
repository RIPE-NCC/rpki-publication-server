package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Actor, ActorRef, Props}
import com.softwaremill.macwire.MacwireMacros.wire
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.fs.RsyncRepositoryWriter
import net.ripe.rpki.publicationserver.{AppConfig, Logging, QueryMessage}

import scala.collection.mutable.ListBuffer

object Accumulator {
  def props(conf: AppConfig): Props = Props(new Accumulator(conf))
}

class Accumulator(conf: AppConfig) extends Actor with Logging {

  import context._

  protected lazy val rsyncWriter = wire[RsyncRepositoryWriter]

  private var rrdpFlusher: ActorRef = _
  private var rsyncFlusher: ActorRef = _

  private val messages: ListBuffer[QueryMessage] = ListBuffer()
  private var scheduled: Boolean = false
  private var latestState: ObjectStore.State = _

  override def preStart() = {
    rrdpFlusher = context.actorOf(RrdpFlusher.props(conf))
    rsyncFlusher = context.actorOf(RsyncFlusher.props())
  }

  override def receive: Receive = {
    case vm@ValidatedMessage(m, state) =>
      messages += m
      latestState = state
      rsyncFlusher ! vm
      handleFlushing()
    case ir: InitRepo =>
      rrdpFlusher ! ir
      rsyncFlusher ! ir
  }

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
