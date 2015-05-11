package net.ripe.rpki.publicationserver


import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import org.slf4j.Logger
import spray.http._
import spray.testkit.ScalatestRouteTest

class PublicationServiceSpec extends FunSuite with Matchers with ScalatestRouteTest with TestFiles with MockitoSugar {
  def actorRefFactory = system

  trait Context {
    def actorRefFactory = system
  }

  def publicationService =  new PublicationService with Context

  test("should return a response with content-type application/rpki-publication") {
    val publishXml = getFile("/publish.xml")

    Post("/", publishXml.mkString) ~> publicationService.myRoute ~> check {
      contentType.toString() should include("application/rpki-publication")
    }
  }

  test("should log a warning when the wrong media type is used in the request") {
    val logSpy = mock[Logger](RETURNS_SMART_NULLS)

    val service = new PublicationService with Context {
      override val serviceLogger = logSpy
    }

    val publishXml = getFile("/publish.xml")
    val contentType = HttpHeaders.`Content-Type`(ContentType(MediaTypes.`application/xml`))

    HttpRequest(HttpMethods.POST, "/", List(contentType), publishXml.mkString) ~> service.myRoute ~> check {
      verify(logSpy).warn("Request uses wrong media type: application/xml")
    }
  }

  test("should return an ok response for a valid publish request") {
    val publishXml = getFile("/publish.xml")
    val publishXmlResponse = getFile("/publishResponse.xml")

    Post("/", publishXml.mkString) ~> publicationService.myRoute ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishXmlResponse.mkString))
    }
  }

  def trim(s: String): String = s.filterNot(c => c == ' ' || c == '\n')

  test("should return an ok response for a valid withdraw request") {
    val withdrawXml = getFile("/withdraw.xml")
    val withdrawXmlResponse = getFile("/withdrawResponse.xml")

    Post("/", withdrawXml.mkString) ~> publicationService.myRoute ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(withdrawXmlResponse.mkString))
    }
  }

  test("should return an error response for a invalid publish request") {
    val invalidPublishXml = getFile("/publishResponse.xml")
    val publishError = getFile("/errorResponse.xml")

    Post("/", invalidPublishXml.mkString) ~> publicationService.myRoute ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishError.mkString))
    }
  }

  test("should leave POST requests to other paths unhandled") {
    Post("/kermit") ~> publicationService.myRoute ~> check {
      handled should be(false)
    }
  }

}
