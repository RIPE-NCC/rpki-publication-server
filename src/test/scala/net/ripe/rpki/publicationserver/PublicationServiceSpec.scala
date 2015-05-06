package net.ripe.rpki.publicationserver

import org.scalatest.{FunSuite, Matchers}
import spray.http.StatusCodes._
import spray.testkit.ScalatestRouteTest

class PublicationServiceSpec extends FunSuite with Matchers with ScalatestRouteTest with PublicationService with TestFiles {
  def actorRefFactory = system

  // TODO Add some implementation here
  def repository = new Repository

  test("should return an ok response for a valid publish request") {
    val publishXml = getFile("/publish.xml")
    val publishXmlResponse = getFile("/publishResponse.xml")

    Post("/", publishXml.mkString) ~> myRoute ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishXmlResponse.mkString))
    }
  }

  def trim(s: String): String = s.filterNot(c => c == ' ' || c == '\n')

  test("should return an ok response for a valid withdraw request") {
    val withdrawXml = getFile("/withdraw.xml")
    val withdrawXmlResponse = getFile("/withdrawResponse.xml")

    Post("/", withdrawXml.mkString) ~> myRoute ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(withdrawXmlResponse.mkString))
    }
  }

  test("should leave POST requests to other paths unhandled") {
    Post("/kermit") ~> myRoute ~> check {
      handled should be(false)
    }
  }

  test("return a MethodNotAllowed error for PUT requests to the root path") {
    Put() ~> sealRoute(myRoute) ~> check {
      status === MethodNotAllowed
      responseAs[String] === "HTTP method not allowed, supported methods: POST"
    }
  }

}
