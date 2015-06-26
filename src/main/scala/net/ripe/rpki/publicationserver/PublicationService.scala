package net.ripe.rpki.publicationserver

import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

import akka.actor.{Props, ActorRef, ActorRefFactory, Actor}
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.parsing.PublicationMessageParser
import net.ripe.rpki.publicationserver.store.Migrations
import org.slf4j.LoggerFactory
import spray.http.HttpHeaders.`Content-Type`
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._

import scala.concurrent.{ExecutionContext, Future}
import scala.io.{BufferedSource, Source}
import scala.util.{Failure, Success}

class PublicationServiceActor(fsWriterFactory: ActorRefFactory => ActorRef, deltaCleanFactory: ActorRefFactory => ActorRef)
  extends Actor with PublicationService with RRDPService {

  def actorRefFactory = context

  def receive = runRoute(publicationRoutes ~ rrdpRoutes)

  override def preStart() = {
    Migrations.migrate()
    val fsWriter = fsWriterFactory(context)
    val deltaCleaner = deltaCleanFactory(context)
    SnapshotState.init(fsWriter, deltaCleaner)
  }
}

object PublicationServiceActor {
  def props(actorRefFactory: ActorRefFactory => ActorRef, actorRefFactory1: ActorRefFactory => ActorRef) =
    Props(new PublicationServiceActor(actorRefFactory, actorRefFactory1))
}

trait PublicationService extends HttpService with RepositoryPath {

  val MediaTypeString = "application/rpki-publication"
  val RpkiPublicationType = MediaType.custom(MediaTypeString)
  MediaTypes.register(RpkiPublicationType)

  val serviceLogger = LoggerFactory.getLogger("PublicationService")

  val msgParser = wire[PublicationMessageParser]

  val healthChecks = wire[HealthChecks]

  val conf = wire[ConfigWrapper]

  implicit val BufferedSourceUnmarshaller =
    Unmarshaller[BufferedSource](spray.http.ContentTypeRange.*) {
      case HttpEntity.NonEmpty(contentType, data) =>
        Source.fromInputStream(new ByteArrayInputStream(data.toByteArray), contentType.charset.nioCharset.toString)
      case HttpEntity.Empty => Source.fromInputStream(new ByteArrayInputStream(Array[Byte]()))
    }

  implicit val singleThreadEC = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

  val publicationRoutes =
    path("") {
      post { parameter ("clientId") { clientId =>
        optionalHeaderValue(checkContentType) { ct =>
          serviceLogger.info("Post request received")
          if (ct.isEmpty) {
            serviceLogger.warn("Request does not specify content-type")
          }
          respondWithMediaType(RpkiPublicationType) {
            entity(as[BufferedSource]){ e =>
              onComplete(Future(processRequest(ClientId(clientId))(e))(executor = singleThreadEC)) {
                case Success(result) =>
                  complete(result)
                case Failure(error) =>
                  serviceLogger.error("Error processing request: ", error)
                  complete(500, error.getMessage)
              }
            }
          }
        }
      }
    } } ~
      path("monitoring" / "healthcheck") {
        get {
          complete(healthChecks.healthString)
        }
      }

  private def processRequest(clientId: ClientId) (xmlMessage: BufferedSource) = {
    def logErrors(errors: Seq[ReplyPdu]): Unit = {
      serviceLogger.warn(s"Request contained ${errors.size} PDU(s) with errors:")
      errors.foreach { e =>
        serviceLogger.info(e.asInstanceOf[ReportError].message.getOrElse(s"Error code: ${e.asInstanceOf[ReportError].code.toString}"))
      }
    }

    val response = msgParser.parse(xmlMessage) match {
      case Right(QueryMessage(pdus)) =>
        val elements = SnapshotState.updateWith(clientId, pdus)
        elements.filter(_.isInstanceOf[ReportError]) match {
          case Seq() =>
            serviceLogger.info("Request handled successfully")
          case errors =>
            logErrors(errors)
        }
        ReplyMsg(elements).serialize

      case Right(ListMessage()) =>
        ReplyMsg(SnapshotState.list(clientId)).serialize

      case Left(msgError) =>
        serviceLogger.warn("Error while handling request: {}", msgError)
        ErrorMsg(msgError).serialize
    }
    response
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


