package net.ripe.rpki.publicationserver

import java.io.ByteArrayInputStream

import akka.actor.Actor
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.fs.SnapshotReader
import org.slf4j.LoggerFactory
import spray.http.HttpHeaders.{`Cache-Control`, `Content-Type`}
import spray.http.MediaTypes._
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._

import scala.io.{BufferedSource, Source}

class PublicationServiceActor extends Actor with PublicationService with RRDPService {

  def actorRefFactory = context

  def receive = runRoute(publicationRoutes ~ rrdpRoutes)

  override def preStart() = {
    val initialSnapshot = SnapshotReader.readSnapshotFromNotification(repositoryPath = conf.locationRepositoryPath, repositoryUri = conf.locationRepositoryUri)
    initialSnapshot match {
      case Left(err@BaseError(_, _)) =>
        serviceLogger.error(s"Error occured while reading initial snapshot: $err")
      case Right(None) =>
        val snapshot = RepositoryState.emptySnapshot
        RepositoryState.writeRepositoryState(snapshot)
        RepositoryState.initializeWith(snapshot)
      case Right(Some(is)) =>
        RepositoryState.initializeWith(is)
    }
  }
}

trait RepositoryPath {
  val repositoryPath = wire[ConfigWrapper].locationRepositoryPath
}

trait PublicationService extends HttpService with RepositoryPath {

  val MediaTypeString = "application/rpki-publication"
  val RpkiPublicationType = MediaType.custom(MediaTypeString)
  MediaTypes.register(RpkiPublicationType)

  val serviceLogger = LoggerFactory.getLogger("PublicationService")

  val msgParser = wire[PublicationMessageParser]

  val healthChecks = wire[HealthChecks]

  lazy val conf = wire[ConfigWrapper]

  implicit val BufferedSourceUnmarshaller =
    Unmarshaller[BufferedSource](spray.http.ContentTypeRange.*) {
      case HttpEntity.NonEmpty(contentType, data) =>
        Source.fromInputStream(new ByteArrayInputStream(data.toByteArray), contentType.charset.nioCharset.toString)
      case HttpEntity.Empty => Source.fromInputStream(new ByteArrayInputStream(Array[Byte]()))
    }

  val publicationRoutes =
    path("") {
      post {
        optionalHeaderValue(checkContentType) { ct =>
          serviceLogger.info("Post request received")
          if (ct.isEmpty) {
            serviceLogger.warn("Request does not specify content-type")
          }
          respondWithMediaType(RpkiPublicationType) {
            entity(as[BufferedSource])(processRequest)
          }
        }
      }
    } ~
      path("monitoring" / "healthcheck") {
        get {
          complete(healthChecks.healthString)
        }
      }

  private def processRequest(xmlMessage: BufferedSource): StandardRoute = {
    def logErrors(errors: Seq[ReplyPdu]): Unit = {
      serviceLogger.warn(s"Request contained ${errors.size} PDU(s) with errors:")
      errors.foreach { e =>
        serviceLogger.info(e.asInstanceOf[ReportError].message.getOrElse(s"Error code: ${e.asInstanceOf[ReportError].code.toString}"))
      }
    }

    val response = msgParser.parse(xmlMessage) match {
      case Right(QueryMessage(pdus)) =>
        val elements = RepositoryState.updateWith(pdus)
        elements.filter(_.isInstanceOf[ReportError]) match {
          case Seq() =>
            serviceLogger.info("Request handled successfully")
          case errors =>
            logErrors(errors)
        }
        ReplyMsg(elements).serialize

      case Right(ListMessage()) =>
        ReplyMsg(RepositoryState.listReply).serialize

      case Left(msgError) =>
        serviceLogger.warn("Error while handling request: {}", msgError)
        ErrorMsg(msgError).serialize
    }
    complete(response)
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


trait RRDPService extends HttpService with RepositoryPath {

  val rrdpRoutes =
    path("notification.xml") {
      serve( s"""$repositoryPath/notification.xml""")
//      respondWithHeaders(`Cache-Control`(CacheDirectives.`no-cache`, CacheDirectives.`no-store`, CacheDirectives.`must-revalidate`)) {
//        getFromFile(s"""$repositoryPath/notification.xml""")
//      }
    } ~
    path(JavaUUID / IntNumber / "snapshot.xml") { (sessionId, serial) =>
      serve( s"""$repositoryPath/$sessionId/$serial/snapshot.xml""")
    } ~
    path(JavaUUID / IntNumber / "delta.xml") { (sessionId, serial) =>
      serve( s"""$repositoryPath/$sessionId/$serial/delta.xml""")
    }

  private def serve(filename: => String) = get {
    respondWithMediaType(`application/xhtml+xml`) {
      getFromFile(filename)
    }
  }

}

