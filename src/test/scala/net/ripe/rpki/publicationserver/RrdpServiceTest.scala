package net.ripe.rpki.publicationserver

import akka.actor.ActorRefFactory
import spray.testkit.ScalatestRouteTest

class RrdpServiceTest extends PublicationServerBaseTest with ScalatestRouteTest {

  def rrdpService = new RRDPService {
    override implicit def actorRefFactory: ActorRefFactory = system
  }

  trait Context {
    def actorRefFactory = system
  }

  test("should return a health check response") {
    Get("/monitoring/healthcheck") ~> rrdpService.monitoringRoutes ~> check {
      val response = responseAs[String]
      response should include ("buildNumber")
    }
  }
}
