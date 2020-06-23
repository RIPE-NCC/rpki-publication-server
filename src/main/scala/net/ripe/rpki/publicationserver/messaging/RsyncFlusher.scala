package net.ripe.rpki.publicationserver.messaging

import akka.actor.{Actor, Props}
import net.ripe.rpki.publicationserver.messaging.Messages.{InitRepo, ValidatedStateMessage}
import net.ripe.rpki.publicationserver.store.ObjectStore._
import net.ripe.rpki.publicationserver.store.fs.RsyncRepositoryWriter
import net.ripe.rpki.publicationserver.{AppConfig, Logging, QueryMessage}

import scala.util.Try

object RsyncFlusher {
  def props(conf: AppConfig) = Props(new RsyncFlusher(conf))
}

class RsyncFlusher(conf: AppConfig) extends Actor with Logging {

  protected val rsyncWriter = new RsyncRepositoryWriter(conf)

  override def receive = {
    case ValidatedStateMessage(m, _) =>
      updateRsyncRepo(m)
    case InitRepo(state) =>
      initRsyncRepo(state)
  }

  private def initRsyncRepo(state: State) = Try {
    rsyncWriter.cleanUpTemporaryDirs()
    rsyncWriter.writeSnapshot(state)
  } recover {
    case e: Exception =>
        e.printStackTrace()
      logger.error(s"Could not write to rsync repo, bailing out: ", e)
      // ThreadDeath is one of the few exceptions that Akka considers fatal, i.e. which can trigger jvm termination
      throw new ThreadDeath
  }

  private def updateRsyncRepo(message: QueryMessage): Unit = {
    logger.debug(s"Writing message to rsync repo:\n$message")
    rsyncWriter.updateRepo(message)
  }
}
