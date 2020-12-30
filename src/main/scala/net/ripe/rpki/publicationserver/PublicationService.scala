package net.ripe.rpki.publicationserver

import java.io.ByteArrayInputStream

import akka.actor._
import akka.http.scaladsl.model._
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
import scala.util.{Failure, Success, Try}
import akka.util.ByteString

object PublicationService {
    val MediaTypeString = "application/rpki-publication"
    val `rpki-publication` = MediaType.customWithFixedCharset("application", "rpki-publication", HttpCharsets.`UTF-8`)
} 

class PublicationService
    (conf: AppConfig, stateActor: ActorRef)
    (implicit val system: ActorSystem) extends Logging {

  implicit val executionContext = system.dispatcher

  val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1) {
    case _: Exception =>
      SupervisorStrategy.Escalate
  }
  
  val msgParser = wire[PublicationMessageParser]

  def verifyContentType(contentType: ContentType) = {
      if (contentType == null) {
        logger.warn("Request does not specify Content-Type")
      } else if (contentType.mediaType != PublicationService.`rpki-publication`) {
        logger.warn(s"Request specifies Content-Type = ${contentType}")
      }
  }

  // TODO Support proper streaming
  implicit val BufferedSourceUnmarshaller: Unmarshaller[HttpEntity, BufferedSource] =
    Unmarshaller.withMaterializer { _ => 
        implicit mat => {
            case HttpEntity.Strict(contentType, data) => 
                verifyContentType(contentType)
                FastFuture.successful(Source.fromInputStream(new ByteArrayInputStream(data.toArray)))            
            case entity                     => 
                verifyContentType(entity.contentType)
                entity.dataBytes
                    .runFold(ByteString.empty)(_ ++ _)
                    .map(data => Source.fromInputStream(new ByteArrayInputStream(data.toArray)))
        }
    }

  import ExecutionContext.Implicits._

  logger.info(s"size limit on publication endpoint: ${conf.publicationEntitySizeLimit} bytes.")
  // The exception to maximum entity size is only applied to publication endpoint, which has client TLS, preventing DOS attacks
  val publicationRoutes = withSizeLimit(conf.publicationEntitySizeLimit) {
      path("") {
        post {
          parameter("clientId") { clientId =>
            entity(as[BufferedSource]) { xmlMessage =>
              val mainPipeline = onComplete {
                try {
                  val r = msgParser.parse(xmlMessage) match {
                    case Right(request) =>
                      processRequest(request, ClientId(clientId))
                    case Left(msgError) =>
                      Future {
                        logger.warn("Error while handling request: {}", msgError)
                        ErrorMsg(msgError)
                      }
                  }
                  r.mapTo[Msg]
                } catch {
                  case e: Exception =>
                    Future {
                      ErrorMsg(BaseError(BaseError.XmlSchemaValidationError, s"XML parsing/validation error: ${e.getMessage}"))
                    }
                }
              }

              mainPipeline {
                case Success(msg@ErrorMsg(BaseError(BaseError.XmlSchemaValidationError, message))) =>
                  logger.error(s"Error parsing POST request with clientId=$clientId", message)
                  complete(HttpResponse(status = 400, entity = HttpEntity(PublicationService.`rpki-publication`, msg.serialize)))

                case Success(result) =>
                  val response = result.serialize
                  complete(HttpResponse(entity = HttpEntity(PublicationService.`rpki-publication`, response)))

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

  private def processRequest[T](parsedMessage: T, clientId: ClientId) = {
    implicit val timeout = Timeout(61.seconds)
    parsedMessage match {
      case queryMessage@QueryMessage(_) =>
        stateActor ? RawMessage(queryMessage, clientId)
      case ListMessage() =>
        stateActor ? RawMessage(ListMessage(), clientId)
    }
  }
}

