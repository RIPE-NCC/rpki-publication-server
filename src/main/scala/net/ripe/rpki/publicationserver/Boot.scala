package net.ripe.rpki.publicationserver

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.fs.SnapshotReader
import org.slf4j.LoggerFactory
import spray.can.Http

import scala.concurrent.duration._
import scala.util.Try

object Boot extends App {

  val conf = wire[ConfigWrapper]

  implicit val system = ActorSystem("on-spray-can")

  val service = system.actorOf(Props[PublicationServiceActor], "publication-service")

  implicit val timeout = Timeout(5.seconds)

  setupLogging()

  val logger = LoggerFactory.getLogger(this.getClass)

  private val initialSnapshot = Try {
    SnapshotReader.readSnapshot(repositoryPath = conf.locationRepositoryPath, repositoryUri = conf.locationRepositoryUri)
  } getOrElse {
    logger.warn(s"No previous notification.xml found in ${conf.locationRepositoryPath}. Starting with empty snapshot")
    SnapshotState.emptySnapshot
  }
  SnapshotState.initializeWith(initialSnapshot)

  IO(Http) ? Http.Bind(service, interface = "::0", port = conf.port)


  def setupLogging() = {
    System.setProperty("LOG_FILE", conf.locationLogfile)
  }
}
