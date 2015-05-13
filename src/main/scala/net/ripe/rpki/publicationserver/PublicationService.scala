package net.ripe.rpki.publicationserver

import akka.actor.Actor
import org.slf4j.LoggerFactory
import spray.http.HttpHeaders.`Content-Type`
import spray.http._
import spray.routing._

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class PublicationServiceActor extends Actor with PublicationService {

  def actorRefFactory = context

  def receive = runRoute(myRoute)
}

import com.softwaremill.macwire.MacwireMacros._

trait PublicationService extends HttpService {

  val MediaTypeString = "application/rpki-publication"
  val RpkiPublicationType = MediaType.custom(MediaTypeString)
  MediaTypes.register(RpkiPublicationType)

  val serviceLogger = LoggerFactory.getLogger("PublicationService")

  val repository = wire[Repository]

  val msgParser = wire[MsgParser]

  val healthChecks = wire[HealthChecks]

  def handlePost = {
    respondWithMediaType(RpkiPublicationType) {
      entity(as[String])(processRequest)
    }
  }

  val myRoute =
    path("") {
      post {
        optionalHeaderValue(checkContentType) { ct =>
          serviceLogger.info("Post request received")
          if (!ct.isDefined) {
            serviceLogger.warn("Request does not specify content-type")
          }
          handlePost
        }
      }
    } ~
    path("monitoring" / "healthcheck") {
      get {
        val response = healthChecks.healthString
        complete {
          response
        }
    }
  }

  def processRequest(xmlMessage: String): StandardRoute = {
    val response = msgParser.process(xmlMessage, repository.update) match {
      case Right(msg) =>
        serviceLogger.info("Request handled successfully")
        msgParser.serialize(msg)
      case Left(msgError) =>
        serviceLogger.warn("Error while handling request: " + msgError)
        msgParser.serialize(msgError)
    }
    complete {
      response
    }
  }

  def checkContentType(header: HttpHeader): Option[ContentType] = header match {

    case `Content-Type`(ct) =>
      if (!MediaTypeString.equals(ct.mediaType.toString())) {
        serviceLogger.warn("Request uses wrong media type: " + ct.mediaType.toString())
      }
      Some(ct)
    case _ => None
  }
}