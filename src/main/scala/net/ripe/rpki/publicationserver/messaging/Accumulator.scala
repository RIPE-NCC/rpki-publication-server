package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Actor, ActorRef, Props}
import com.softwaremill.macwire.MacwireMacros.wire
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.ObjectStore.State
import net.ripe.rpki.publicationserver.store.fs.RsyncRepositoryWriter
import net.ripe.rpki.publicationserver.{AppConfig, Logging, QueryMessage}

import scala.collection.mutable.ListBuffer
import scala.util.Try

object Accumulator {
  def props(conf: AppConfig): Props = Props(new Accumulator(conf))
}

class Accumulator(conf: AppConfig) extends Actor with Logging {

  import context._

  protected lazy val rsyncWriter = wire[RsyncRepositoryWriter]

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
      updateRsyncRepo(m)
    case ir : InitRepo =>
      flusher ! ir
      initRsyncRepo(ir.state)
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

  def initRsyncRepo(state: State) = {
    Try {
      rsyncWriter.writeSnapshot(state)
    } recover {
      case e: Exception =>
        logger.error(s"Could not write to rsync repo, bailing out: ", e)
        // ThreadDeath is one of the few exceptions that Akka considers fatal, i.e. which can trigger jvm termination
        throw new ThreadDeath
    }
  }

  def updateRsyncRepo(message: QueryMessage): Unit = {
    logger.debug(s"Writing message to rsync repo:\n$message")
    rsyncWriter.updateRepo(message)
  }
}
