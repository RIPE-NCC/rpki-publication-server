package net.ripe.rpki.publicationserver

import org.specs2.mutable.Specification
import spray.http.StatusCodes._
import spray.testkit.Specs2RouteTest

class PublicationServiceSpec extends Specification with Specs2RouteTest with PublicationService with TestFiles {
  def actorRefFactory = system

  // TODO Add some implmentation here
  def repository = new Repository

  "PublicationService" should {

    "return an ok response for a valid publish request" in {
      val publishXml = getFile("/publish.xml")
      val publishXmlResponse = getFile("/publishResponse.xml")

      Post("/", publishXml.mkString) ~> myRoute ~> check {
        responseAs[String] must be(publishXmlResponse.mkString)
      }
    }

    "return an ok response for a valid withdraw request" in {
      val withdrawXml = getFile("/withdraw.xml")
      val withdrawXmlResponse = getFile("/withdrawResponse.xml")

      Post("/", withdrawXml.mkString) ~> myRoute ~> check {
        responseAs[String] must be(withdrawXmlResponse.mkString)
      }
    }

    "leave POST requests to other paths unhandled" in {
      Post("/kermit") ~> myRoute ~> check {
        handled must beFalse
      }
    }

    "return a MethodNotAllowed error for PUT requests to the root path" in {
      Put() ~> sealRoute(myRoute) ~> check {
        status === MethodNotAllowed
        responseAs[String] === "HTTP method not allowed, supported methods: POST"
      }
    }
  }
}
