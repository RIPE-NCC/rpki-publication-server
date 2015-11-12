package net.ripe.rpki.publicationserver

import spray.http.CacheDirectives
import spray.http.HttpHeaders.`Cache-Control`
import spray.routing.{HttpService}

trait RRDPService extends HttpService with RepositoryPath {
  val immutableContentValiditySeconds: Long = 31536001 // ~one year

  val rrdpRoutes =
    pathPrefix ("notification.xml") {
      pathEnd {
        get {
          respondWithHeader(`Cache-Control`(CacheDirectives.`no-cache`)) {
            getFromFile(s"$repositoryPath/notification.xml")
          }
        }
      }
    } ~
    pathPrefix("") {
      get {
        respondWithHeader(`Cache-Control`(CacheDirectives.`max-age`(immutableContentValiditySeconds))) {
          getFromDirectory(s"$repositoryPath")
        }
      }
    }
}