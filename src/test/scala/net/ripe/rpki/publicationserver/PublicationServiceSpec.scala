package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.UUID

import org.mockito.Mockito._
import org.slf4j.Logger
import spray.http.HttpHeaders.RawHeader
import spray.http._
import spray.testkit.ScalatestRouteTest

import scala.io.Source

class PublicationServiceSpec extends PublicationServerBaseSpec with ScalatestRouteTest {
  def actorRefFactory = system

  trait Context {
    def actorRefFactory = system
  }

  def publicationService = new PublicationService with Context {
  }

  before {
    SnapshotState.initializeWith(SnapshotState.emptyChangeSet)
  }

  test("should return a response with content-type application/rpki-publication") {
    POST("/", getFile("/publish.xml")) ~> publicationService.publicationRoutes ~> check {
      contentType.toString() should include("application/rpki-publication")
    }
  }

  test("should log a warning when a message contained an error") {
    val logSpy = mock[Logger](RETURNS_SMART_NULLS)

    val service = new PublicationService with Context {
      override val serviceLogger = logSpy
    }

    // The withdraw will fail because the SnapshotState is empty
    val withdrawXml = getFile("/withdraw.xml")
    val contentType = HttpHeaders.`Content-Type`(MediaType.custom("application/rpki-publication"))

    HttpRequest(HttpMethods.POST, "/", List(contentType), withdrawXml.mkString) ~> service.publicationRoutes ~> check {
      verify(logSpy).warn("Request contained 1 PDU(s) with errors:")
      verify(logSpy).info("No object [rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer] found.")
    }
  }

  test("should log a warning when the wrong media type is used in the request") {
    val logSpy = mock[Logger](RETURNS_SMART_NULLS)

    val service = new PublicationService with Context {
      override val serviceLogger = logSpy
    }

    val publishXml = getFile("/publish.xml")
    val contentType = HttpHeaders.`Content-Type`(ContentType(MediaTypes.`application/xml`))

    HttpRequest(HttpMethods.POST, "/", List(contentType), publishXml.mkString) ~> service.publicationRoutes ~> check {
      verify(logSpy).warn("Request uses wrong media type: {}", "application/xml")
    }
  }

  test("should return an ok response for a valid publish request") {
    val publishXml = getFile("/publish.xml")
    val publishXmlResponse = getFile("/publishResponse.xml")

    POST("/", publishXml) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishXmlResponse.mkString))
    }
  }

  test("should return the tag in the response if it was present in the publish request") {
    val publishXml = getFile("/publishWithTag.xml")
    val publishXmlResponse = getFile("/publishWithTagResponse.xml")

    POST("/", publishXml) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishXmlResponse.mkString))
    }
  }

  test("should return an ok response for a valid withdraw request") {
    val pdus = Map(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer") -> (Base64("bla"), Hash("deadf00d")))
    val state0 = ChangeSet(Map.empty)
    SnapshotState.initializeWith(state0)

    val withdrawXml = getFile("/withdraw.xml")
    val withdrawXmlResponse = getFile("/withdrawResponse.xml")

    POST("/", withdrawXml) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(withdrawXmlResponse.mkString))
    }
  }

  test("should return the tag in the response if it was present in the withdraw request") {
    val pdus = Map(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer") -> (Base64("bla"), Hash("deadf00d")))
    val state0 = ChangeSet(Map.empty)
    SnapshotState.initializeWith(state0)

    val withdrawXml = getFile("/withdrawWithTag.xml")
    val withdrawXmlResponse = getFile("/withdrawWithTagResponse.xml")

    POST("/", withdrawXml) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(withdrawXmlResponse.mkString))
    }
  }

  test("should return an error response for a invalid publish request") {
    val invalidPublishXml = getFile("/publishResponse.xml")
    val publishError = getFile("/errorResponse.xml")

    POST("/", invalidPublishXml) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishError.mkString))
    }
  }

  test("should return a list response for list request") {
    POST("/", getFile("/publish.xml")) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }

    val listXml = getFile("/list.xml")
    val listXmlResponse = getFile("/listResponse.xml")
    POST("/", listXml) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(listXmlResponse.mkString))
    }
  }

  test("should execute list query even if <list/> doesn't go first") {
    POST("/", getFile("/publish.xml")) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }

    val publishXml = getFile("/dubiousListRequest.xml")
    val listXmlResponse = getFile("/listResponse.xml")

    POST("/", publishXml) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(listXmlResponse.mkString))
    }
  }

  test("should leave POST requests to other paths unhandled") {
    Post("/kermit") ~> publicationService.publicationRoutes ~> check {
      handled should be(false)
    }
  }

  test("should return a health check response") {
    Get("/monitoring/healthcheck") ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      response should include ("buildNumber")
    }
  }

  private def POST(uriString: String, content: Source) = HttpRequest(
    method = HttpMethods.POST,
    uri = uriString,
    headers = List(RawHeader("Content-type", "application/rpki-publication")),
    entity = content.mkString)

}
