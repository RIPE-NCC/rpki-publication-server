package net.ripe.rpki.publicationserver

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import com.typesafe.config._

object Boot extends App {

  implicit val system = ActorSystem("on-spray-can")

  val service = system.actorOf(Props[PublicationServiceActor], "publication-service")

  implicit val timeout = Timeout(5.seconds)

  val conf = ConfigFactory.load()
  val serverPort = conf.getInt("port")

  IO(Http) ? Http.Bind(service, interface = "::0", port = serverPort)
}
