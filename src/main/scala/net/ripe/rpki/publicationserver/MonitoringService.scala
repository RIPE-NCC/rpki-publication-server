package net.ripe.rpki.publicationserver

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

trait MonitoringService extends RepositoryPath {

  def healthChecks: HealthChecks

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
}
