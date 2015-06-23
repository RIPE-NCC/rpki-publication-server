package net.ripe.rpki.publicationserver

import spray.http.CacheDirectives
import spray.http.HttpHeaders.`Cache-Control`
import spray.http.MediaTypes._
import spray.routing.HttpService

trait RRDPService extends HttpService with RepositoryPath {
  val immutableContentValiditySeconds: Long = 31536000 // ~one year

  val rrdpRoutes =
    path("notification.xml") {
      respondWithHeader(`Cache-Control`(CacheDirectives.`no-cache`)) {
        serve(s"$repositoryPath/notification.xml")
      }
    } ~
      path(JavaUUID / IntNumber / "snapshot.xml") { (sessionId, serial) =>
        serveImmutableContent(s"$repositoryPath/$sessionId/$serial/snapshot.xml")
      } ~
      path(JavaUUID / IntNumber / "delta.xml") { (sessionId, serial) =>
        serveImmutableContent(s"$repositoryPath/$sessionId/$serial/delta.xml")
      }

  private def serveImmutableContent(filename: => String) = {
    respondWithHeader(`Cache-Control`(CacheDirectives.`max-age`(immutableContentValiditySeconds))) {
      serve(filename)
    }
  }

  private def serve(filename: => String) = get {
    respondWithMediaType(`application/xhtml+xml`) {
      getFromFile(filename)
    }
  }

}