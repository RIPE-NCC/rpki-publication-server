package net.ripe.rpki.publicationserver

import akka.actor.Actor
import spray.routing._
import spray.http._
import MediaTypes._

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

// this trait defines our service behavior independently from the service actor
trait PublicationService extends HttpService {

  def repository: Repository

  val myRoute =
    path("") {
      post {
        // TODO protocol should be application/rpki-publication
        respondWithMediaType(`application/xml`) {
          entity(as[String]) { xmlMessage =>
            val response = MsgXml.parse(xmlMessage) match {
              case Right(msg) =>
                val responseMsg = repository.update(msg)
                MsgXml.serialize(responseMsg)
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