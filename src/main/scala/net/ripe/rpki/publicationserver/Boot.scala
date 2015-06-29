package net.ripe.rpki.publicationserver

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.store.fs.{DeltaCleanActor, FSWriterActor}
import org.slf4j.LoggerFactory
import spray.can.Http

import scala.concurrent.duration._

object Boot extends App {

  val conf = wire[ConfigWrapper]

  setupLogging()

  implicit val system = ActorSystem("on-spray-can")

  val deltaCleanerFactory = (context:ActorRefFactory) => context.actorOf(DeltaCleanActor.props)
  val fsWriterFactory = (context:ActorRefFactory) => context.actorOf(FSWriterActor.props(deltaCleanerFactory))

  val service = system.actorOf(PublicationServiceActor.props(fsWriterFactory, deltaCleanerFactory), "publication-service")

  implicit val timeout = Timeout(5.seconds)

  IO(Http) ? Http.Bind(service, interface = "::0", port = conf.port)

  def setupLogging() = {
    System.setProperty("LOG_FILE", conf.locationLogfile)
    LoggerFactory.getLogger(this.getClass).info("Starting up the publication server ...")
  }
}
