package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Actor, Props}
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.messaging.Messages.{InitRepo, ValidatedMessage}
import net.ripe.rpki.publicationserver.store.ObjectStore._
import net.ripe.rpki.publicationserver.store.fs.RsyncRepositoryWriter
import net.ripe.rpki.publicationserver.{Logging, QueryMessage}

import scala.util.Try

object RsyncFlusher {
  def props() = Props(new RsyncFlusher())
}

class RsyncFlusher extends Actor with Logging {

  protected lazy val rsyncWriter = wire[RsyncRepositoryWriter]

  override def receive = {
    case ValidatedMessage(m, state) =>
      updateRsyncRepo(m)
    case ir: InitRepo =>
      initRsyncRepo(ir.state)
  }

  private def initRsyncRepo(state: State) = Try {
    rsyncWriter.writeSnapshot(state)
  } recover {
    case e: Exception =>
      logger.error(s"Could not write to rsync repo, bailing out: ", e)
      // ThreadDeath is one of the few exceptions that Akka considers fatal, i.e. which can trigger jvm termination
      throw new ThreadDeath
  }

  private def updateRsyncRepo(message: QueryMessage) = {
    logger.debug(s"Writing message to rsync repo:\n$message")
    rsyncWriter.updateRepo(message)
  }
}
