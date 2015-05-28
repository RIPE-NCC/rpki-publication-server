package net.ripe.rpki.publicationserver

import java.io.ByteArrayInputStream

import akka.actor.Actor
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.fs.RepositoryWriter
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

  val repositoryWriter = wire[RepositoryWriter]

  lazy val conf = wire[ConfigWrapper]

  val sessionId = conf.currentSessionId

  lazy val repositoryUri = conf.locationRepositoryUri

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

  private def notificationUrl(snapshot: SnapshotState) = repositoryUri + "/" + sessionId + "/" + snapshot.serial + "/snapshot.xml"

  private def processRequest(xmlMessage: BufferedSource): StandardRoute = {
    val response = msgParser.parse(xmlMessage) match {
      case Right(QueryMessage(pdus)) =>
        val elements = SnapshotState.updateWith(pdus)
        elements.filter(_.isInstanceOf[ReportError]) match {
          case Seq() =>
            writeSnapshotAndNotification
            serviceLogger.info("Request handled successfully")
          case errors =>
            serviceLogger.warn(s"Request contained one or more pdus with errors: $errors")
        }
        ReplyMsg(elements).serialize

      case Right(ListMessage()) =>
        ReplyMsg(SnapshotState.listReply).serialize

      case Left(msgError) =>
        serviceLogger.warn("Error while handling request: {}", msgError)
        ErrorMsg(msgError).serialize
    }
    complete(response)
  }

  def writeSnapshotAndNotification = {
    val newSnapshot = SnapshotState.get
    repositoryWriter.writeSnapshot(repositoryPath, newSnapshot)
    NotificationState.update(sessionId, notificationUrl(newSnapshot), newSnapshot)
    // TODO If the following fails we'd like to roll back the updating of the snapshot and the notification state somehow ..
    repositoryWriter.writeNotification(repositoryPath, NotificationState.get)
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
      respondWithHeaders(`Cache-Control`(CacheDirectives.`no-cache`, CacheDirectives.`no-store`, CacheDirectives.`must-revalidate`)) {
        getFromFile(s"""$repositoryPath/notification.xml""")
      }
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

