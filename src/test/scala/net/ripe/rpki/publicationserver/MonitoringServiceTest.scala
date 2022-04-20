package net.ripe.rpki.publicationserver

import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.mockito.Mockito.{RETURNS_SMART_NULLS, when}

class MonitoringServiceTest extends PublicationServerBaseTest with ScalatestRouteTest with MonitoringService  {

  trait Context {
    def actorRefFactory = system
  }

  test("should return a health check response") {
    Get("/monitoring/healthcheck") ~> monitoringRoutes ~> check {
      val response = responseAs[String]
      response should include ("commit")
    }
  }

  override def healthChecks: HealthChecks = new HealthChecks(new AppConfig() {
    override lazy val pgConfig: PgConfig = pgTestConfig
  })
}
