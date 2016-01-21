package net.ripe.rpki.publicationserver

import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import javax.xml.stream.XMLStreamException

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.messaging.Messages.RawMessage
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.parsing.PublicationMessageParser
import net.ripe.rpki.publicationserver.store.Migrations
import org.slf4j.LoggerFactory
import spray.http.HttpHeaders.`Content-Type`
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success}

object PublicationServiceActor {
  def props(conf: AppConfig) = Props(new PublicationServiceActor(conf))
}

class PublicationServiceActor(conf: AppConfig) extends HttpServiceActor {

  def receive = runRoute(publicationRoutes)

  val stateActor: ActorRef = context.system.actorOf(StateActor.props(conf))

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1) {
    case _: Exception =>
      SupervisorStrategy.Escalate
  }

  val MediaTypeString = "application/rpki-publication"
  val RpkiPublicationType = MediaType.custom(MediaTypeString)
  MediaTypes.register(RpkiPublicationType)

  val serviceLogger = LoggerFactory.getLogger("PublicationService")

  val msgParser = wire[PublicationMessageParser]

  override def preStart() = {
    Migrations.migrate()
  }

  implicit val BufferedSourceUnmarshaller =
    Unmarshaller[BufferedSource](spray.http.ContentTypeRange.*) {
      case HttpEntity.NonEmpty(contentType, data) =>
        Source.fromInputStream(new ByteArrayInputStream(data.toByteArray), contentType.charset.nioCharset.toString)
      case HttpEntity.Empty => Source.fromInputStream(new ByteArrayInputStream(Array[Byte]()))
    }

  import ExecutionContext.Implicits._

  val publicationRoutes =
    path("") {
      post {
        parameter("clientId") { clientId =>
          optionalHeaderValue(checkContentType) { ct =>
            serviceLogger.debug("Post request received")
            if (ct.isEmpty) {
              serviceLogger.warn("Request does not specify content-type")
            }
            respondWithMediaType(RpkiPublicationType) {
              entity(as[BufferedSource]) { xmlMessage =>
                onComplete {
                  Future(msgParser.parse(xmlMessage)).flatMap(
                    parsedMessage => processRequest(ClientId(clientId), parsedMessage))
                } {
                  case Success(result) =>
                    complete(result.serialize)
                  case Failure(error: XMLStreamException) =>
                    serviceLogger.error(s"Error parsing POST request with clientId=$clientId", error)
                    complete(400, error.getMessage)
                  case Failure(error) =>
                    serviceLogger.error(s"Error processing POST request with clientId=$clientId", error)
                    complete(500, error.getMessage)
                }
              }
            }
          }
        }
      }
    }


  private def processRequest[T](clientId: ClientId, parsedMessage: Either[BaseError, T]): Future[Msg] = {
    def logErrors(errors: Seq[ReplyPdu]): Unit = {
      serviceLogger.warn(s"Request contained ${errors.size} PDU(s) with errors:")
      errors.foreach { e =>
        serviceLogger.info(e.asInstanceOf[ReportError].message.getOrElse(s"Error code: ${e.asInstanceOf[ReportError].code.toString}"))
      }
    }

    val response = parsedMessage match {
      case Right(request) =>
        processRequest(request, clientId)
      case Left(msgError) =>
        Future {
          serviceLogger.warn("Error while handling request: {}", msgError)
          ErrorMsg(msgError)
        }
    }
    response.mapTo[Msg]
  }

  private def processRequest[T](parsedMessage: T, clientId: ClientId) = {
    implicit val timeout = Timeout(61.seconds)
    parsedMessage match {
      case queryMessage@QueryMessage(pdus) =>
        stateActor ? RawMessage(queryMessage, clientId)
      case ListMessage() =>
        stateActor ? RawMessage(ListMessage(), clientId)
    }
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


