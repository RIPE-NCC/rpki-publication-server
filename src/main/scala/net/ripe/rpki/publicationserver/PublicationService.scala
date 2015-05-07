package net.ripe.rpki.publicationserver

import akka.actor.Actor
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

  val RpkiPublicationType = MediaType.custom("application/rpki-publication")
  MediaTypes.register(RpkiPublicationType)

  def repository: Repository

  val myRoute =
    path("") {
      post {
        respondWithMediaType(RpkiPublicationType) {
          entity(as[String]) { xmlMessage =>
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
        }
      }
    }
}