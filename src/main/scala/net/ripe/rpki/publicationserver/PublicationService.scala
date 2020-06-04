package net.ripe.rpki.publicationserver

import java.io.ByteArrayInputStream

import akka.actor.{Actor, _}
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpHeader, MediaType}
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
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success}

class PublicationService(conf: AppConfig, stateActor: ActorRef)  {


  lazy val stateActor = context.system.actorOf(StateActor.props(conf))

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1) {
    case _: Exception =>
      SupervisorStrategy.Escalate
  }

  val MediaTypeString = "application/rpki-publication"
  val RpkiPublicationType = MediaType.custom(MediaTypeString, true)

  // TODO: Find how to do this on akka-http
  //  MediaTypes.register(RpkiPublicationType)

  val msgParser = wire[PublicationMessageParser]

  // TODO:
  //  - Verify HttpEntity.Strict vs HttpEntity.NonEmpty
  implicit val BufferedSourceUnmarshaller: Unmarshaller[HttpEntity, BufferedSource] =
    Unmarshaller.withMaterializer(_ => implicit mat => {
      case HttpEntity.Strict(_, data) => FastFuture.successful(Source.fromInputStream(new ByteArrayInputStream(data.toArray)))
      case HttpEntity.Empty => FastFuture.successful(Source.fromInputStream(new ByteArrayInputStream(Array[Byte]())))
    })

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
            //respondWithMediaType(RpkiPublicationType) {
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
            //}
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


