package net.ripe.rpki.publicationserver

import akka.actor.Actor
import com.softwaremill.macwire.MacwireMacros._
import org.slf4j.LoggerFactory
import spray.http.HttpHeaders.`Content-Type`
import spray.http._
import spray.routing._

class PublicationServiceActor extends Actor with PublicationService {

  def actorRefFactory = context

  def receive = runRoute(myRoute)
}

trait PublicationService extends HttpService {

  val MediaTypeString = "application/rpki-publication"
  val RpkiPublicationType = MediaType.custom(MediaTypeString)
  MediaTypes.register(RpkiPublicationType)

  val serviceLogger = LoggerFactory.getLogger("PublicationService")

  val repository = wire[Repository]

  val msgParser = wire[PublicationMessageParser]

  val healthChecks = wire[HealthChecks]

  val myRoute =
    path("") {
      post {
        optionalHeaderValue(checkContentType) { ct =>
          serviceLogger.info("Post request received")
          if (!ct.isDefined) {
            serviceLogger.warn("Request does not specify content-type")
          }
          respondWithMediaType(RpkiPublicationType) {
            entity(as[String])(processRequest)
          }
        }
      }
    } ~
    path("monitoring" / "healthcheck") {
      get {
        complete(healthChecks.healthString)
      }
    }

  private def processRequest(xmlMessage: String): StandardRoute = {
    val response = msgParser.process(xmlMessage) match {
      case Right(queryMessage) =>
        serviceLogger.info("Request handled successfully")
        val elements = SnapshotState.updateWith(queryMessage.pdus)
        ReplyMsg(elements).serialize  // TODO check for errors during updating the snapshot and log them

      case Left(msgError) =>
        serviceLogger.warn("Error while handling request: {}", msgError)
        ErrorMsg(msgError).serialize
    }
    complete(response)
  }

  private def checkContentType(header: HttpHeader): Option[ContentType] = header match {
    case `Content-Type`(ct) =>
      if (!MediaTypeString.equals(ct.mediaType.toString())) {
        serviceLogger.warn("Request uses wrong media type: {}", ct.mediaType.toString())
      }
      Some(ct)
    case _ => None
  }
}
