package net.ripe.rpki.publicationserver

import java.io.FileInputStream
import java.security.KeyStore

import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, SupervisorStrategy}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.softwaremill.macwire._
import com.softwaremill.macwire.akkasupport._
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext, Http}
import javax.net.ssl._
import net.ripe.logging.SysStreamsLogger
import net.ripe.rpki.publicationserver.store.XodusDB
import net.ripe.rpki.publicationserver.metrics._
import org.slf4j.{LoggerFactory, Logger}
import akka.pattern.ask

import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client._

import scala.concurrent.duration._



object Boot extends App {
  lazy val conf = wire[AppConfig]
  lazy val logger = setupLogging(conf)
  new PublicationServerApp(conf, logger).run()

  def setupLogging(conf: AppConfig) = {
    System.setProperty("LOG_FILE", conf.locationLogfile)
    SysStreamsLogger.bindSystemStreams()
    LoggerFactory.getLogger(this.getClass)
  }
}

class PublicationServerApp(conf: AppConfig, logger: Logger) extends RRDPService {
    
  def run() {
    XodusDB.init(conf.storePath)

    logger.info("Starting up the publication server ...")

    implicit val system = ActorSystem("0")
    implicit val materializer = ActorMaterializer()
    implicit val dispatcher = system.dispatcher

    implicit val timeout = Timeout(5.seconds)

    val registry = CollectorRegistry.defaultRegistry
    val metrics = new Metrics(registry)
    val metricsApi = new MetricsApi(registry)

    val stateActor: ActorRef = system.actorOf(StateActor.props(conf, metrics))

    val publicationService = new PublicationService(conf, stateActor)

    Http().bindAndHandle(
      publicationService.publicationRoutes,
      interface = "::0",
      port = conf.publicationPort,
      connectionContext = ConnectionContext.https(sslContext()),
      settings = conf.publicationServerSettings.get      
    )

    Http().bindAndHandle(
      rrdpAndMonitoringRoutes ~ metricsApi.routes,
      interface = "::0",
      port = conf.rrdpPort
    )
  }

  private def sslContext(): SSLContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(getKeyManagers, getTrustManagers(conf), null)
    sslContext
  }  

  private def getTrustManagers(conf: AppConfig): Array[TrustManager] = {
    if (conf.publicationServerTrustStoreLocation.isEmpty) {
      logger.info(
        "publication.server.truststore.location is not set, skipping truststore init"
      )
      null
    } else {
      val trustStore = KeyStore.getInstance("JKS")
      val tsPassword: Array[Char] =
        if (conf.publicationServerTrustStorePassword.isEmpty) {
          null
        } else {
          conf.publicationServerTrustStorePassword.toCharArray
        }
      logger.info(
        s"Loading HTTPS certificate from ${conf.publicationServerTrustStoreLocation}"
      )
      trustStore.load(
        new FileInputStream(conf.publicationServerTrustStoreLocation),
        tsPassword
      )
      val tmf = TrustManagerFactory.getInstance("SunX509")
      tmf.init(trustStore)
      tmf.getTrustManagers
    }
  }

  private def getKeyManagers: Array[KeyManager] = {
    if (conf.publicationServerKeyStoreLocation.isEmpty) {
      if (conf.getConfig.getBoolean(
            "publication.spray.can.server.ssl-encryption"
          )) {
        logger.error(
          "publication.spray.can.server.ssl-encryption is ON, but publication.server.keystore.location " +
            "is not defined. THIS WILL NOT WORK!"
        )
      } else {
        logger.info(
          "publication.server.keystore.location is not set, skipping keystore init"
        )
      }
      null
    } else {
      val ksPassword = if (conf.publicationServerKeyStorePassword.isEmpty) {
        null
      } else {
        conf.publicationServerKeyStorePassword.toCharArray
      }
      val keyStore = KeyStore.getInstance("JKS")
      logger.info(
        s"Loading HTTPS certificate from ${conf.publicationServerKeyStoreLocation}"
      )
      keyStore.load(
        new FileInputStream(conf.publicationServerKeyStoreLocation),
        ksPassword
      )
      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(keyStore, ksPassword)
      kmf.getKeyManagers
    }
  } 
}
