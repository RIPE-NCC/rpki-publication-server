package net.ripe.rpki.publicationserver.integration

import akka.http.scaladsl.model._
import java.net.URL
import javax.net.ssl._
import net.ripe.rpki.publicationserver.PublicationService
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import scala.concurrent.Await
import scala.concurrent.duration._
import java.security.KeyStore
import java.io.FileInputStream
import akka.http.scaladsl.ConnectionContext
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import akka.util.ByteString
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.xml.NodeSeq
import akka.http.scaladsl.Http
import akka.http.scaladsl.HttpExt

class PublicationServerClient() {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  private val publicationPort = 7766
  private val rrdpPort = 7788

  private def https = {
    val httpImpl = Http()    
    // TODO Find a not deprecatreds way to do it
    val sslConfig = AkkaSSLConfig().mapSettings(s =>
      s.withLoose(s.loose.withDisableHostnameVerification(true))
    )
    val context = ConnectionContext.https(sslContext(), Some(sslConfig))
    httpImpl.setDefaultClientHttpsContext(context)
    httpImpl
  }

  private def http = Http()

  private def sendMsg(clientId: String)(message: NodeSeq): String = 
    sendMsg(clientId, PublicationService.`rpki-publication`)(message)
  
  private def sendMsg(clientId: String, mediaType: MediaType.WithFixedCharset)(message: NodeSeq): String = {
    val msg =
      <msg type="query" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
        {message}
      </msg> 

    responseAsString(https) {
        HttpRequest(
            method = HttpMethods.POST,
            uri = s"https://localhost:$publicationPort?clientId=$clientId",
            entity = HttpEntity(mediaType, msg.toString())
        )
    }
  }

  private def responseAsString(http: HttpExt)(request: HttpRequest): String = {
    val f = http
      .singleRequest(request)
      .flatMap(_.entity.toStrict(10.seconds))
      .flatMap { entity =>
        entity.dataBytes
          .runFold(ByteString.empty) { case (acc, b) => acc ++ b }
          .map(_.decodeString(StandardCharsets.UTF_8))
      }

    Await.result(f, Duration(10, SECONDS))
  }

  def withdraw(clientId: String, url: String, hash: String): String =
    sendMsg(clientId) {
      <withdraw uri={url} hash={hash}/>
    }

  def publish(clientId: String, url: String, content: String, mediaType: MediaType.WithFixedCharset): String =
    sendMsg(clientId, mediaType) {
      <publish uri={url}>
         {content}
      </publish>
    }

  def publish(clientId: String, url: String, content: String): String =
    sendMsg(clientId) {
      <publish uri={url}>
         {content}
      </publish>
    }

  def list(clientId: String): String =
    sendMsg(clientId) {
      <list/>
    }

  def getMetrics() : String = {          
    responseAsString(http) {
        HttpRequest(method = HttpMethods.GET, uri = s"http://localhost:$rrdpPort/metrics")
    }
  }

  // SSL configuration
  private val theSamePasswordEverywhere = "123456"

  private val publicationServerTrustStoreLocation =
    "./src/test/resources/certificates/serverTrustStore.ks"
  private val publicationServerKeyStoreLocation =
    "./src/test/resources/certificates/serverKeyStore.ks"

  private val clientTrustStoreLocation =
    "./src/test/resources/certificates/clientTrustStore.ks"
  private val clientKeyStoreLocation =
    "./src/test/resources/certificates/clientKeyStore.ks"

  private def sslContext(): SSLContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(getKeyManagers, getTrustManagers(), null)
    sslContext
  }

  private def getTrustManagers(): Array[TrustManager] = {
    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(
      new FileInputStream(clientTrustStoreLocation),
      theSamePasswordEverywhere.toCharArray()
    )
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(trustStore)
    tmf.getTrustManagers
  }

  private def getKeyManagers: Array[KeyManager] = {
    val keyStore = KeyStore.getInstance("JKS")
    val ksPassword = theSamePasswordEverywhere.toCharArray()
    keyStore.load(new FileInputStream(clientKeyStoreLocation), ksPassword)
    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(keyStore, ksPassword)
    kmf.getKeyManagers
  }

}
