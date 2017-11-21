package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{Files, Path}
import java.util.UUID

import akka.testkit.TestKit.awaitCond
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.{DBConfig, Migrations}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import spray.http.HttpHeaders.RawHeader
import spray.http._
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try
import scala.xml.Elem

abstract class PublicationServerBaseTest extends FunSuite with BeforeAndAfter with Matchers with MockitoSugar with TestLogSetup with ScalatestRouteTest {

  protected def waitTime: FiniteDuration = 30.seconds

  DBConfig.useMemoryDatabase = true
  Migrations.migrate()

  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))

  def trim(s: String): String = s.filterNot(_.isWhitespace)

  def POST(uriString: String, content: String) = HttpRequest(
    method = HttpMethods.POST,
    uri = uriString,
    headers = List(RawHeader("Content-type", "application/rpki-publication")),
    entity = content)

  def updateState(service: PublicationServiceActor, pdus: Seq[QueryPdu], clientId: ClientId = ClientId("1234")) = {
    POST(s"/?clientId=${clientId.value}", Util.xmlSeq(pdus).mkString) ~> service.publicationRoutes ~> check {
      status.value should be("200 OK")
    }
  }

  def updateStateWithCallback[T](service: PublicationServiceActor, pdus: Seq[QueryPdu], clientId: ClientId)(callback: => T) = {
    POST(s"/?clientId=${clientId.value}", Util.xmlSeq(pdus).mkString) ~> service.publicationRoutes ~> check {
      status.value should be("200 OK")
      callback
    }
  }

  def checkFileExists(path: Path): Unit = {
    awaitCond(Files.exists(path), max = waitTime)
  }

  def checkFileAbsent(path: Path): Unit = {
    awaitCond(Files.notExists(path), max = waitTime)
  }

  def findSessionDir(path: Path): File = {
    val maybeFiles = Option(path.toFile.listFiles)

    awaitCond(maybeFiles.exists(_.exists { f =>
      Try(UUID.fromString(f.getName)).isSuccess
    }), max = waitTime)

    maybeFiles.flatMap(_.find { f =>
      Try(UUID.fromString(f.getName)).isSuccess
    }).get
  }
}
