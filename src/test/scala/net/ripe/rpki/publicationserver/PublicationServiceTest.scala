package net.ripe.rpki.publicationserver

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.model._
import org.scalatest.BeforeAndAfterAll


class PublicationServiceTest extends PublicationServerBaseTest with Hashing with BeforeAndAfterAll {

  private val conf = new AppConfig() {
    override lazy val pgConfig = pgTestConfig
  }

  def publicationService = new PublicationService(conf, testMetrics)

  private val objectStore = createPgStore

  before {
    objectStore.clear()
  }

  override def afterAll(): Unit = {
    cleanUp()
  }

  test("should return a response with content-type application/rpki-publication") {
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      contentType.toString() should include("application/rpki-publication")
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
    val service = publicationService

    val bytes = Bytes.fromBase64(Base64("DEADBEEF"))
    val certUrl = "rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"
    val pdus = Seq(PublishQ(new URI(certUrl), None, None, bytes))
    updateState(service, pdus)

    val withdrawXml = xml(WithdrawQ(new URI(certUrl), tag = None, hashOf(bytes)))
    val withdrawXmlResponse = getFile("/withdrawResponse.xml")

    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(withdrawXmlResponse.mkString))
    }
  }

  test("should return an ok response for a valid withdraw request (hash casing problem)") {
    val service = publicationService

    val bytes = Bytes.fromBase64(Base64("DEADBEEF"))
    val pdus = Seq(PublishQ(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"), None, None, bytes))
    updateState(service, pdus)

    val withdrawXml = xml(WithdrawQ(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"), tag = None, hashOf(bytes)))
    val withdrawXmlResponse = getFile("/withdrawResponse.xml")

    POST("/?clientId=1234", withdrawXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(withdrawXmlResponse.mkString))
    }
  }

  test("should return an ok response for a valid replace request") {
    val service = publicationService

    val uri = new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
    val bytes = Bytes.fromBase64(Base64("DEADBEEF"))
    val pdus = Seq(PublishQ(uri, None, None, bytes))
    updateState(service, pdus)

    val publishWithHashXml = xml(PublishQ(uri, None, Some(hashOf(bytes)), bytes))
    val publishWithHashXmlResponse = getFile("/publishWithHashXmlResponse.xml")

    POST("/?clientId=1234", publishWithHashXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishWithHashXmlResponse.mkString))
    }
  }

  test("should return an ok response for a valid replace request (hash causing problem)") {
    val service = publicationService

    val uri = new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
    val bytes = Bytes.fromBase64(Base64("DEADBEEF"))
    val pdus = Seq(PublishQ(uri, None, None, bytes))
    updateState(service, pdus)

    val publishWithHashXml = xml(PublishQ(uri, None, Some(hashOf(bytes)), bytes))
    val publishWithHashXmlResponse = getFile("/publishWithHashXmlResponse.xml")

    POST("/?clientId=1234", publishWithHashXml.mkString) ~> service.publicationRoutes ~> check {
      val response = responseAs[String]
      trim(response) should be(trim(publishWithHashXmlResponse.mkString))
    }
  }

  test("should return the tag in the response if it was present in the withdraw request") {
    val service = publicationService

    val bytes = Bytes.fromBase64(Base64("DEADBEEF"))
    val pdus = Seq(PublishQ(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"), None, None, bytes))
    updateState(service, pdus)

    val withdrawXml = xml(WithdrawQ(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"), tag = Some("123"), hashOf(bytes)))
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

  test("should return <error ...> is base64 is invalid") {
    val service = publicationService
    POST("/?clientId=1234", getFile("/publishBrokenBase64.xml").mkString) ~> service.publicationRoutes ~> check {        
      response.status.isSuccess should be(false)
      val responseStr = responseAs[String]
      responseStr should include("XML parsing/validation error: \"BROKENBASE")
    }
  }
}
