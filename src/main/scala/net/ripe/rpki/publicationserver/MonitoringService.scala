package net.ripe.rpki.publicationserver

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.softwaremill.macwire._

trait MonitoringService extends RepositoryPath {
  val oneDayInSeconds: Long = 24 * 60 * 60

  val healthChecks: HealthChecks = wire[HealthChecks]

  val monitoringRoutes: Route =
    path("monitoring" / "healthcheck") {
      get {
        complete(healthChecks.healthString)
      }
    }
}
