package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{Files, Path}
import java.util.UUID

import akka.testkit.TestKit.awaitCond
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.XodusDB
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

  var tempXodusDir: File = _
  def initStore() = {
    tempXodusDir = Files.createTempDirectory("rpki-pub-server-test").toFile
    XodusDB.init(tempXodusDir.getAbsolutePath)
  }

  def cleanStore() = {
    // TODO Make it less ugly
    Runtime.getRuntime.exec("rm -Rf \"" + tempXodusDir.getAbsolutePath + "\"")
  }

  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))

  def trim(s: String): String = s.filterNot(_.isWhitespace)

  def POST(uriString: String, content: String) = HttpRequest(
    method = HttpMethods.POST,
    uri = uriString,
    headers = List(RawHeader("Content-type", "application/rpki-publication")),
    entity = content)

  def xmlSeq(pdus: Seq[QueryPdu]): Elem = xml(pdus: _*)

  def xml(pdus: QueryPdu*): Elem =
    <msg type="query" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      {pdus.map {
      case PublishQ(uri, None, None, b) =>
        <publish uri={uri.toASCIIString}>
          {Bytes.toBase64(b).value}
        </publish>

      case PublishQ(uri, None, Some(hash), b) =>
        <publish uri={uri.toASCIIString} hash={hash}>
          {Bytes.toBase64(b).value}
        </publish>

      case PublishQ(uri, Some(tag), None, b) =>
        <publish uri={uri.toASCIIString} tag={tag}>
          {Bytes.toBase64(b).value}
        </publish>

      case PublishQ(uri, Some(tag), Some(hash), b) =>
        <publish uri={uri.toASCIIString} hash={hash} tag={tag}>
          {Bytes.toBase64(b).value}
        </publish>

      case WithdrawQ(uri, None, hash) =>
          <withdraw uri={uri.toASCIIString} hash={hash}/>

      case WithdrawQ(uri, Some(tag), hash) =>
          <withdraw uri={uri.toASCIIString} hash={hash} tag={tag}/>

      case ListQ(None) => <list/>
      case ListQ(Some(tag)) => <list tag={tag}/>
    }}
    </msg>


  def updateState(service: PublicationServiceActor, pdus: Seq[QueryPdu], clientId: ClientId = ClientId("1234")) = {

    POST(s"/?clientId=${clientId.value}", xmlSeq(pdus).mkString) ~> service.publicationRoutes ~> check {
      status.value should be("200 OK")
    }
  }

  def updateStateWithCallback[T](service: PublicationServiceActor, pdus: Seq[QueryPdu], clientId: ClientId)(callback: => T) = {
    POST(s"/?clientId=${clientId.value}", xmlSeq(pdus).mkString) ~> service.publicationRoutes ~> check {
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
