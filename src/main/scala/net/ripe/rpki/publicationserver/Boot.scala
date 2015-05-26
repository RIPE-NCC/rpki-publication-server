package net.ripe.rpki.publicationserver

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config._
import net.ripe.rpki.publicationserver.fs.SnapshotReader
import spray.can.Http

import scala.concurrent.duration._

object Boot extends App {

  implicit val system = ActorSystem("on-spray-can")

  val service = system.actorOf(Props[PublicationServiceActor], "publication-service")

  implicit val timeout = Timeout(5.seconds)

  val conf = ConfigFactory.load()
  val serverPort = conf.getInt("port")

  setupLogging(conf)

  SnapshotReader.readSnapshot(conf.getString("locations.repository.path"))

  IO(Http) ? Http.Bind(service, interface = "::0", port = serverPort)


  def setupLogging(conf: Config) = {
    System.setProperty("LOG_FILE", conf.getString("locations.logfile"))
  }
}
