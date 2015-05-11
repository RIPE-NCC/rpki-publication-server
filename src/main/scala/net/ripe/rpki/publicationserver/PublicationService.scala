package net.ripe.rpki.publicationserver

import akka.actor.Actor
import org.slf4j.Logger
import spray.http.HttpHeaders.`Content-Type`
import spray.http._
import spray.routing._

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class PublicationServiceActor extends Actor with PublicationService {

  def actorRefFactory = context

  def receive = runRoute(myRoute)

  override def repository: Repository = {
    // TODO Implement proper sharing
    new Repository()
  }
}

trait PublicationService extends HttpService {

  val MediaTypeString = "application/rpki-publication"
  val RpkiPublicationType = MediaType.custom(MediaTypeString)
  MediaTypes.register(RpkiPublicationType)

  val serviceLogger = org.slf4j.LoggerFactory.getLogger("PublicationService")

  def repository: Repository

  def handlePost = {
    respondWithMediaType(RpkiPublicationType) {
      entity(as[String])(processRequest)
    }
  }

  val myRoute =
    path("") {
      post {
        optionalHeaderValue(checkContentType) { ct =>
          if (!ct.isDefined) {
            serviceLogger.warn("Request does not specify content-type")
          }
          handlePost
        }
      }
    }

  def processRequest(xmlMessage: String): StandardRoute = {
    val response = MsgParser.process(xmlMessage, repository.update) match {
      case Right(msg) =>
        MsgParser.serialize(msg)
      case Left(msgError) =>
        MsgParser.serialize(msgError)
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