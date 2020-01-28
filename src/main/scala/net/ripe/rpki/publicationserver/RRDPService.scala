package net.ripe.rpki.publicationserver

import java.nio.file.{Files, Paths}

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.{CacheDirectives, `Cache-Control`}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.softwaremill.macwire._



trait RRDPService extends RepositoryPath {
  val immutableContentValiditySeconds: Long = 24 * 60 * 60 // ~one day

  val healthChecks = wire[HealthChecks]

  val rrdpAndMonitoringRoutes = rrdpRoutes ~ monitoringRoutes

  val rrdpRoutes: Route =
    path("notification.xml") {
      respondWithHeader(`Cache-Control`(CacheDirectives.`max-age`(60), CacheDirectives.`no-transform`)) {

        complete {
          try {
            HttpResponse(200, Nil, Files.readAllBytes(Paths.get(s"$repositoryPath/notification.xml")))
          } catch {
            case e: Throwable =>
              HttpResponse(404, Nil, e.getMessage)
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
    getFromFile(filename)
  }

}
