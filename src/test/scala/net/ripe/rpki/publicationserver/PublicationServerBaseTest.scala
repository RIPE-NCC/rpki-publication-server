package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{Files, Path}
import java.util.{Comparator, UUID}

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestKit.awaitCond
import io.prometheus.client.CollectorRegistry
import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.postgresql.PgStore
import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try
import scala.xml.Elem

abstract class PublicationServerBaseTest extends AnyFunSuite with BeforeAndAfter with Matchers with TestLogSetup with ScalatestRouteTest {

  protected def waitTime: FiniteDuration = 30.seconds

  var tempXodusDir: File = _

  val pgTestConfig = new AppConfig().pgConfig

  protected def createPgStore = {
    PgStore.migrateDB(pgTestConfig)
    PgStore.get(pgTestConfig)
  }

  implicit lazy val testMetrics = Metrics.get(new CollectorRegistry(true));

  def cleanDir(path: Path) = {
    Try {
      Files.walk(path)
        .sorted(Comparator.reverseOrder())
        .forEach(p => p.toFile.delete())
    }
  }

  def deleteOnExit(path: Path): Unit ={
    path.toFile.deleteOnExit()
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = cleanDir(path)
    }))
  }

  def withTempDir[R](f : File => R) : R = {
      val dir = Files.createTempDirectory("rpki-pub-server-test").toFile
      try {        
        f(dir)
      } finally {
        cleanDir(dir.toPath)
      }      
  }

  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))

  def trim(s: String): String = s.filterNot(_.isWhitespace)

  def POST(uriString: String, content: String) = HttpRequest(
    method = HttpMethods.POST,
    uri = uriString,
    headers = List(RawHeader("Content-Type", "application/rpki-publication")),
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


  def updateState(service: PublicationService, pdus: Seq[QueryPdu], clientId: ClientId = ClientId("1234")) = {
    POST(s"/?clientId=${clientId.value}", xmlSeq(pdus).mkString) ~> service.publicationRoutes ~> check {
      status.value should be("200 OK")
    }
  }

  def updateStateWithCallback[T](service: PublicationService, pdus: Seq[QueryPdu], clientId: ClientId)(callback: => T) = {
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
