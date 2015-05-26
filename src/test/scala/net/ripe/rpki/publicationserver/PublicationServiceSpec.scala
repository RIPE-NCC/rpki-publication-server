package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.fs.SnapshotWriter
import org.mockito.Matchers._
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
    SnapshotState.initializeWith(SnapshotState.emptySnapshot)
  }

  test("should return a response with content-type application/rpki-publication") {
    POST("/", getFile("/publish.xml")) ~> publicationService.publicationRoutes ~> check {
      contentType.toString() should include("application/rpki-publication")
    }
  }

  test("should write a snapshot to the filesystem when a message is succesfully processed") {
    val snapshotWriterSpy = mock[SnapshotWriter](RETURNS_SMART_NULLS)

    val service = new PublicationService with Context {
      override val snapshotWriter = snapshotWriterSpy
    }

    val publishXml = getFile("/publish.xml")

    POST("/", publishXml) ~> service.publicationRoutes ~> check {
      verify(snapshotWriterSpy).writeSnapshot(anyString(), any[SnapshotState])
    }
  }

  test("should log a warning and should not write a snapshot to the filesystem when a message contained an error") {
    val logSpy = mock[Logger](RETURNS_SMART_NULLS)
    val snapshotWriterSpy = mock[SnapshotWriter](RETURNS_SMART_NULLS)

    val service = new PublicationService with Context {
      override val serviceLogger = logSpy
      override val snapshotWriter = snapshotWriterSpy
    }

    // The withdraw will fail because the SnapshotState is empty
    val withdrawXml = getFile("/withdraw.xml")
    val contentType = HttpHeaders.`Content-Type`(MediaType.custom("application/rpki-publication"))

    POST("/", withdrawXml) ~> service.publicationRoutes ~> check {
      verify(logSpy).warn("Request contained one or more pdu's with errors")
      verifyNoMoreInteractions(snapshotWriterSpy)
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
    val state0 = SnapshotState(UUID.randomUUID(), BigInt(1), pdus)
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
    val state0 = SnapshotState(UUID.randomUUID(), BigInt(1), pdus)
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
