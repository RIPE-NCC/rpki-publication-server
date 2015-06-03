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

object Boot extends App with Logging {

  val conf = wire[ConfigWrapper]

  setupLogging()

  implicit val system = ActorSystem("on-spray-can")

  val service = system.actorOf(Props[PublicationServiceActor], "publication-service")

  implicit val timeout = Timeout(5.seconds)

  IO(Http) ? Http.Bind(service, interface = "::0", port = conf.port)

  def setupLogging() = {
    System.setProperty("LOG_FILE", conf.locationLogfile)
  }
}
