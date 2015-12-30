package net.ripe.rpki.publicationserver

import java.nio.file.{Files, Paths}

import akka.actor.{Actor, Props}
import com.softwaremill.macwire.MacwireMacros.wire
import spray.http.HttpHeaders.`Cache-Control`
import spray.http.MediaTypes._
import spray.http._
import spray.routing.HttpService

object RRDPServiceActor {
  def props() = Props(new RRDPServiceActor())
}


class RRDPServiceActor() extends Actor with RRDPService {
  def actorRefFactory = context
  def receive = runRoute(rrdpRoutes ~ monitoringRoutes)
}


trait RRDPService extends HttpService with RepositoryPath {
  val immutableContentValiditySeconds: Long = 31536000 // ~one year

  val healthChecks = wire[HealthChecks]

  val rrdpRoutes =
    path("notification.xml") {
      respondWithHeader(`Cache-Control`(CacheDirectives.`max-age`(60), CacheDirectives.`no-transform`)) {
        respondWithMediaType(`application/xhtml+xml`) {
          complete {
            try {
              HttpResponse(200, Files.readAllBytes(Paths.get(s"$repositoryPath/notification.xml")))
            } catch {
              case e: Throwable =>
                HttpResponse(404, e.getMessage)
            }
          }
        }
      }
    } ~
      path(JavaUUID / IntNumber / "snapshot.xml") { (sessionId, serial) =>
        serveImmutableContent(s"$repositoryPath/$sessionId/$serial/snapshot.xml")
      } ~
      path(JavaUUID / IntNumber / "delta.xml") { (sessionId, serial) =>
        serveImmutableContent(s"$repositoryPath/$sessionId/$serial/delta.xml")
      }

  val monitoringRoutes =
    path("monitoring" / "healthcheck") {
      get {
        complete(healthChecks.healthString)
      }
    }

  private def serveImmutableContent(filename: => String) = {
    respondWithHeader(`Cache-Control`(CacheDirectives.`max-age`(immutableContentValiditySeconds), CacheDirectives.`no-transform`)) {
      serve(filename)
    }
  }

  private def serve(filename: => String) = get {
    respondWithMediaType(`application/xhtml+xml`) {
      getFromFile(filename)
    }
  }

}
