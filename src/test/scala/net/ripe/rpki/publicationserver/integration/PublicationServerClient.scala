package net.ripe.rpki.publicationserver.integration

import akka.http.scaladsl.model._
import java.net.URL
import net.ripe.rpki.publicationserver.PublicationService
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import scala.concurrent.Await
import scala.concurrent.duration._


class PublicationServerClient {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  def publish(url: URL, content: String): Unit = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = "https://localhost:7766/",
      entity = HttpEntity(PublicationService.`rpki-publication`, content)
    )
    val x = Http().singleRequest(request)
    val response: HttpResponse = Await.result(x, Duration(10, SECONDS))
    
    ()
  }

}
