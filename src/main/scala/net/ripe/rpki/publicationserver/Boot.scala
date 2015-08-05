package net.ripe.rpki.publicationserver

import java.io.PrintStream

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.logging.LoggingOutputStream
import net.ripe.rpki.publicationserver.store.fs.FSWriterActor
import org.apache.log4j.{Logger, Level}
import org.slf4j.LoggerFactory
import spray.can.Http

import scala.concurrent.duration._

object Boot extends App {

  val conf = wire[AppConfig]

  setupLogging()

  implicit val system = ActorSystem("on-spray-can")

  val fsWriterFactory = (context:ActorRefFactory) => context.actorOf(FSWriterActor.props)

  val service = system.actorOf(PublicationServiceActor.props(fsWriterFactory), "publication-service")

  implicit val timeout = Timeout(5.seconds)

  IO(Http) ? Http.Bind(service, interface = "::0", port = conf.port)

  def setupLogging() = {
    System.setProperty("LOG_FILE", conf.locationLogfile)
    System.setErr(new PrintStream( new LoggingOutputStream(Logger.getRootLogger, Level.ERROR), true))
    LoggerFactory.getLogger(this.getClass).info("Starting up the publication server ...")
  }
}
