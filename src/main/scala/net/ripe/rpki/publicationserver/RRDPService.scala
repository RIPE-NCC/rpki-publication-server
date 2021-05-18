package net.ripe.rpki.publicationserver

import java.nio.file.{Files, Paths}

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{CacheDirectives, `Cache-Control`}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.ContentTypeResolver



trait RRDPService extends RepositoryPath {
  val oneDayInSeconds: Long = 24 * 60 * 60

  def healthChecks: HealthChecks

  val rrdpContentType = ContentType(MediaTypes.`application/xhtml+xml`, HttpCharsets.`US-ASCII`)

  val rrdpRoutes: Route =
    path("notification.xml") {
      respondWithHeader(`Cache-Control`(CacheDirectives.`max-age`(60), CacheDirectives.`no-transform`)) {
        complete {
          try {              
            HttpResponse(
                status = 200, 
                entity = HttpEntity(
                    rrdpContentType,
                    Files.readAllBytes(Paths.get(s"$repositoryPath/notification.xml"))))
          } catch {
            case e: Throwable =>
              HttpResponse(404, Nil, e.getMessage)
          }
        }
      }
    } ~
      path(JavaUUID / IntNumber) { (sessionId, serial) =>
        serveImmutableContent(s"$repositoryPath/$sessionId/$serial")
      }

  val monitoringRoutes: Route =
    path("monitoring" / "healthcheck") {
      get {
        complete(healthChecks.healthString)
      }
    }

  val rrdpAndMonitoringRoutes: Route = rrdpRoutes ~ monitoringRoutes


  private def serveImmutableContent(directory: => String) = {
    respondWithHeader(
        `Cache-Control`(
            CacheDirectives.`max-age`(oneDayInSeconds), 
            CacheDirectives.`no-transform`)) {
                get {
                  getFromDirectory(directory)(ContentTypeResolver.withDefaultCharset(HttpCharsets.`US-ASCII`))
                }    
        }
  }

}
