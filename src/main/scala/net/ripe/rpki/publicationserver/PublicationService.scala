package net.ripe.rpki.publicationserver

import java.io.ByteArrayInputStream

import akka.actor._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.pattern.ask
import akka.util.Timeout
import com.softwaremill.macwire._
import javax.xml.stream.XMLStreamException
import net.ripe.rpki.publicationserver.messaging.Messages.RawMessage
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.parsing.PublicationMessageParser

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success}

class PublicationService(conf: AppConfig, stateActor: ActorRef) extends Logging {

  val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1) {
    case _: Exception =>
      SupervisorStrategy.Escalate
  }

  val MediaTypeString = "application/rpki-publication"
  val `rpki-publication` = MediaType.customWithFixedCharset("application", "rpki-publication", HttpCharsets.`UTF-8`)

  val msgParser = wire[PublicationMessageParser]

  // TODO:
  //  - Verify HttpEntity.Strict vs HttpEntity.NonEmpty
  implicit val BufferedSourceUnmarshaller: Unmarshaller[HttpEntity, BufferedSource] =
    Unmarshaller.withMaterializer { _ => 
        implicit mat => {
            case HttpEntity.Strict(_, data) => FastFuture.successful(Source.fromInputStream(new ByteArrayInputStream(data.toArray)))
            case HttpEntity.Empty           => FastFuture.successful(Source.fromInputStream(new ByteArrayInputStream(Array[Byte]())))
        }
    }

  import ExecutionContext.Implicits._

  val publicationRoutes =
    path("") {
      post {
        parameter("clientId") { clientId =>
          optionalHeaderValue(checkContentType) { ct =>
            logger.debug("Post request received")
            if (ct.isEmpty) {
              logger.warn("Request does not specify content-type")
            }
            entity(as[BufferedSource]) { xmlMessage =>
              onComplete {
                  processRequest(ClientId(clientId), msgParser.parse(xmlMessage))
              } {
                case Success(result) =>
                  val response = result.serialize
                  complete(HttpResponse(entity = HttpEntity(`rpki-publication`, response)))

                case Failure(error: XMLStreamException) =>
                  logger.error(s"Error parsing POST request with clientId=$clientId", error)
                  complete(400, error.getMessage)

                case Failure(error) =>
                  logger.error(s"Error processing POST request with clientId=$clientId", error)
                  complete(500, error.getMessage)
              }
            }
          }
        }
      }
    }

  private def processRequest[T](clientId: ClientId, parsedMessage: Either[BaseError, T]): Future[Msg] = {
    val response = parsedMessage match {
      case Right(request) =>
        processRequest(request, clientId)
      case Left(msgError) =>
        Future {
          logger.warn("Error while handling request: {}", msgError)
          ErrorMsg(msgError)
        }
    }
    response.mapTo[Msg]
  }

  private def processRequest[T](parsedMessage: T, clientId: ClientId) = {
    implicit val timeout = Timeout(61.seconds)
    parsedMessage match {
      case queryMessage@QueryMessage(_) =>
        stateActor ? RawMessage(queryMessage, clientId)
      case ListMessage() =>
        stateActor ? RawMessage(ListMessage(), clientId)
    }
  }

  private def checkContentType(header: HttpHeader): Option[ContentType] = header match {
    case `Content-Type`(ct) =>
      if (MediaTypeString != ct.mediaType.toString()) {
        logger.warn("Request uses wrong media type: {}", ct.mediaType.toString())
      }
      Some(ct)
    case _ => None
  }
}


