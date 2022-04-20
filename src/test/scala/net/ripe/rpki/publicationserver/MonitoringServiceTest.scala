package net.ripe.rpki.publicationserver

import akka.http.scaladsl.testkit.ScalatestRouteTest

class MonitoringServiceTest extends PublicationServerBaseTest with ScalatestRouteTest with MonitoringService  {

  trait Context {
    def actorRefFactory = system
  }

  test("should return a health check response") {
    Get("/monitoring/healthcheck") ~> monitoringRoutes ~> check {
      val response = responseAs[String]
      response should include ("buildNumber")
    }
  }
}
