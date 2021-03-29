package net.ripe.rpki.publicationserver

import akka.actor._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.parsing.PublicationMessageParser
import net.ripe.rpki.publicationserver.store.postresql.{PgStore, RollbackException}

import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.stream.XMLStreamException
import scala.concurrent.Future
import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success}

object PublicationService {
    val MediaTypeString = "application/rpki-publication"
    val `rpki-publication` = MediaType.customWithFixedCharset("application", "rpki-publication", HttpCharsets.`UTF-8`)
}

class PublicationService(conf: AppConfig, metrics: Metrics)
    (implicit val system: ActorSystem) extends Logging {

  implicit val executionContext = system.dispatcher

  val msgParser = new PublicationMessageParser

  lazy val objectStore = PgStore.get(conf.pgConfig)

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
                      ErrorMsg(BaseError("xml_error", s"XML parsing/validation error: ${e.getMessage}"))
                    }
                }
              }

              mainPipeline {
                case Success(msg@ErrorMsg(BaseError("xml_error", message))) =>
                  logger.error(s"Error parsing POST request with clientId=$clientId: $message", message)
                  complete(HttpResponse(status = 400, entity = HttpEntity(PublicationService.`rpki-publication`, msg.serialize)))

                case Success(result) =>
                  val response = result.serialize
                  complete(HttpResponse(entity = HttpEntity(PublicationService.`rpki-publication`, response)))

                case Failure(error: XMLStreamException) =>
                  logger.error(s"Error parsing POST request with clientId=$clientId: XML stream error: $error", error)
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
    Future {
      parsedMessage match {
        case queryMessage@QueryMessage(_) =>
          processQueryMessage(queryMessage, clientId)
        case ListMessage() =>
          // TODO ??? implement tags for list query
          processListMessage(clientId, None)
      }
    }
  }

  private def processQueryMessage(queryMessage: QueryMessage, clientId: ClientId) = {
    try {
      implicit val m = metrics
      objectStore.applyChanges(queryMessage, clientId)
      ReplyMsg {
        queryMessage.pdus.map {
          case PublishQ(uri, tag, _, _) => PublishR(uri, tag)
          case WithdrawQ(uri, tag, _) => WithdrawR(uri, tag)
        }
      }
    } catch {
      case e: RollbackException => ErrorMsg(e.error)
      case e: Exception => ErrorMsg(BaseError("other_error", s"Unknown error: ${e.getMessage}"))
    }
  }

  def processListMessage(clientId: ClientId, tag: Option[String]) =
    ReplyMsg {
      objectStore.list(clientId).map { case (url, hash) =>
        ListR(URI.create(url), hash, tag)
      }
    }

}
