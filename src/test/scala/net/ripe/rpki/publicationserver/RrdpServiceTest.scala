package net.ripe.rpki.publicationserver

import akka.actor.ActorRefFactory
import akka.http.scaladsl.testkit.ScalatestRouteTest

class RrdpServiceTest extends PublicationServerBaseTest with ScalatestRouteTest with RRDPService  {


  trait Context {
    def actorRefFactory = system
  }

  test("should return a health check response") {
    Get("/monitoring/healthcheck") ~> rrdpAndMonitoringRoutes ~> check {
      val response = responseAs[String]
      response should include ("commit")
    }
  }
}
