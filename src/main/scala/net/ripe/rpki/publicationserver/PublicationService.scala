package net.ripe.rpki.publicationserver

import akka.actor.Actor
import spray.http._
import spray.routing._

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class PublicationServiceActor extends Actor with PublicationService {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  // this actor only runs our route, but you could add
  // other things here, like request stream processing
  // or timeout handling
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
            val response = MsgXml.process(xmlMessage, repository.update) match {
              case Right(msg) =>
                MsgXml.serialize(msg)
              case Left(msgError) =>
                MsgXml.serialize(msgError)
            }
            complete {
              response
            }
          }
        }
      }
    }
}