package net.ripe.rpki.publicationserver

import java.net.URI

import akka.actor.Props
import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.model.{Delta, ClientId}
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.fs.{RsyncRepositoryWriter, FSWriterActor}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.slf4j.Logger
import spray.http._
import spray.testkit.ScalatestRouteTest

import scala.util.Try

class PublicationServiceTest extends PublicationServerBaseTest with ScalatestRouteTest {

  val theRsyncWriter = mock[RsyncRepositoryWriter]

  class TestFSWriter extends FSWriterActor {
    override lazy val rsyncWriter = theRsyncWriter
  }

  private val fsWriterRef = TestActorRef(Props(new TestFSWriter))

  def actorRefFactory = system

  trait Context {
    def actorRefFactory = system
  }

  def publicationService = {
    val service = new PublicationService with Context
    service.init(fsWriterRef)
    service
  }

  val objectStore = new ObjectStore

  before {
    objectStore.clear()
    when(theRsyncWriter.writeDelta(any[Delta])).thenReturn(Try {})
  }

  test("should return a response with content-type application/rpki-publication") {
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
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

    HttpRequest(HttpMethods.POST, "/?clientId=1234", List(contentType), withdrawXml.mkString) ~> service.publicationRoutes ~> check {
      verify(logSpy).warn("Request contained 1 PDU(s) with errors:")
      verify(logSpy).info("No object [rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer] found.")
    }
  }

  test("should log a warning when the wrong media type is used in the request") {
    val logSpy = mock[Logger](RETURNS_SMART_NULLS)

    val service = new PublicationService with Context {
      override val serviceLogger = logSpy
    }
    service.init(fsWriterRef)

    val publishXml = getFile("/publish.xml")
    val contentType = HttpHeaders.`Content-Type`(ContentType(MediaTypes.`application/xml`))

    HttpRequest(HttpMethods.POST, "/?clientId=1234", List(contentType), publishXml.mkString) ~> service.publicationRoutes ~> check {
      verify(logSpy).warn("Request uses wrong media type: {}", "application/xml")
    }
  }

  test("should return an ok response for a valid publish request") {
    val publishXml = getFile("/publish.xml")
    val publishXmlResponse = getFile("/publishResponse.xml")

    POST("/?clientId=1234", publishXml.mkString) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishXmlResponse.mkString))
    }
  }

  test("should return the tag in the response if it was present in the publish request") {
    val publishXml = getFile("/publishWithTag.xml")
    val publishXmlResponse = getFile("/publishWithTagResponse.xml")

    POST("/?clientId=1234", publishXml.mkString) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishXmlResponse.mkString))
    }
  }

  test("should return an ok response for a valid withdraw request") {
    val pdus = Seq(PublishQ(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"), None, None, Base64("bla")))
    val service = publicationService
    service.updateWith(ClientId("1234"), pdus)

    val withdrawXml = getFile("/withdraw.xml")
    val withdrawXmlResponse = getFile("/withdrawResponse.xml")

    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(withdrawXmlResponse.mkString))
    }
  }

  test("should return the tag in the response if it was present in the withdraw request") {
    val service = publicationService
    val pdus = Seq(PublishQ(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"), None, None, Base64("bla")))
    service.updateWith(ClientId("1234"), pdus)

    val withdrawXml = getFile("/withdrawWithTag.xml")
    val withdrawXmlResponse = getFile("/withdrawWithTagResponse.xml")

    POST("/?clientId=1234", withdrawXml.mkString) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(withdrawXmlResponse.mkString))
    }
  }

  test("should return an error response for a invalid publish request") {
    val invalidPublishXml = getFile("/publishResponse.xml")
    val publishError = getFile("/errorResponse.xml")

    POST("/?clientId=1234", invalidPublishXml.mkString) ~> publicationService.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishError.mkString))
    }
  }

  test("should return a list response for list request") {
    val service = publicationService
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> service.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }

    val listXml = getFile("/list.xml")
    val listXmlResponse = getFile("/listResponse.xml")
    POST("/?clientId=1234", listXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(listXmlResponse.mkString))
    }
  }

  test("should execute list query even if <list/> doesn't go first") {
    val service = publicationService
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> service.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }

    val listXml = getFile("/dubiousListRequest.xml")
    val listXmlResponse = getFile("/listResponse.xml")

    POST("/?clientId=1234", listXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(listXmlResponse.mkString))
    }
  }

  test("should list only the published object of the specified client") {
    val service = publicationService
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> service.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }
    POST("/?clientId=1235", getFile("/publish_2.xml").mkString) ~> service.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }

    val listXml = getFile("/list.xml")
    val listXmlResponse = getFile("/listResponse.xml")
    POST("/?clientId=1234", listXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(listXmlResponse.mkString))
    }
  }

  test("should list both published objects of the specified client") {
    val service = publicationService
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> service.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }
    POST("/?clientId=1234", getFile("/publish_2.xml").mkString) ~> service.publicationRoutes ~> check {
      response.status.isSuccess should be(true)
    }

    val listXml = getFile("/list.xml")
    val listXmlResponse = getFile("/listResponse_2.xml")
    POST("/?clientId=1234", listXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(listXmlResponse.mkString))
    }
  }

  test("should leave POST requests to other paths unhandled") {
    Post("/kermit") ~> publicationService.publicationRoutes ~> check {
      handled should be(false)
    }
  }
}
