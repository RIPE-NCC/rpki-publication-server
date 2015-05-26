package net.ripe.rpki.publicationserver

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.softwaremill.macwire.MacwireMacros._
import com.typesafe.config._
import net.ripe.rpki.publicationserver.fs.SnapshotReader
import spray.can.Http

import scala.concurrent.duration._

object Boot extends App {

  val conf = wire[ConfigWrapper].getConfig

  implicit val system = ActorSystem("on-spray-can")

  val service = system.actorOf(Props[PublicationServiceActor], "publication-service")

  implicit val timeout = Timeout(5.seconds)

  val serverPort = conf.getInt("port")

  setupLogging(conf)

  SnapshotReader.readSnapshot(conf.getString("locations.repository.path"))

  IO(Http) ? Http.Bind(service, interface = "::0", port = serverPort)


  def setupLogging(conf: Config) = {
    System.setProperty("LOG_FILE", conf.getString("locations.logfile"))
  }
}
