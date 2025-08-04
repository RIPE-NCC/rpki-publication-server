package net.ripe.rpki.publicationserver

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.{CacheDirectives, `Cache-Control`}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.ContentTypeResolver

import java.nio.file.{Files, Paths}



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
      pathPrefix(JavaUUID / IntNumber) { (sessionId, serial) =>
        serveImmutableContent(s"$repositoryPath/$sessionId/$serial")
      }

  val monitoringRoutes: Route =
    path("monitoring" / "healthcheck") {
      get {
        complete(healthChecks.healthString)
      }
    } ~ 
    path("monitoring" / "readiness") {
      get {
        healthChecks.readinessResponse
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
