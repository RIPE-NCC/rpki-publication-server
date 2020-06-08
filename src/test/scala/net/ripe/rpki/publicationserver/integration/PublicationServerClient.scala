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


class PublicationServerClient() {

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    private lazy val http = {
        val h = Http()
        val ssl = sslContext()        
        // TODO Find a not deprecatreds way to do it
        val sslConfig = AkkaSSLConfig().mapSettings(s => s.withLoose(s.loose.withDisableHostnameVerification(true)))
        val https = ConnectionContext.https(sslContext(), Some(sslConfig))        
        h.setDefaultClientHttpsContext(https)
        h
    }

    def send(clientId: String, url: String, content: String): String = {
        val message = 
            <msg type="reply" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
                <publish uri={url}>
                    {content}
                </publish>
            </msg>
            
        val request = HttpRequest(
            method = HttpMethods.POST,
            uri = s"https://localhost:7766?clientId=$clientId",
            entity = HttpEntity(PublicationService.`rpki-publication`, message.toString)
        )

        val f = http.singleRequest(request)
                    .flatMap(_.entity.toStrict(10.seconds))
                    .flatMap { entity => 
                        entity.dataBytes
                            .runFold(ByteString.empty) { case (acc, b) => acc ++ b }
                            .map(_.decodeString(StandardCharsets.UTF_8))        
                    }

        Await.result(f, Duration(10, SECONDS))        
    }

    private val theSamePasswordEverywhere = "123456"

    private val publicationServerTrustStoreLocation = "./src/test/resources/certificates/serverTrustStore.ks"
    private val publicationServerKeyStoreLocation   = "./src/test/resources/certificates/serverKeyStore.ks"    

    private val clientTrustStoreLocation = "./src/test/resources/certificates/clientTrustStore.ks"
    private val clientKeyStoreLocation   = "./src/test/resources/certificates/clientKeyStore.ks"
    

    private def sslContext(): SSLContext = {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(getKeyManagers, getTrustManagers(), null)        
        sslContext
    }  

    private def getTrustManagers(): Array[TrustManager] = {
        val trustStore = KeyStore.getInstance("JKS")
        trustStore.load(new FileInputStream(clientTrustStoreLocation), theSamePasswordEverywhere.toCharArray())
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
