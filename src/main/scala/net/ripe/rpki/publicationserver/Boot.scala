package net.ripe.rpki.publicationserver

import java.io.FileInputStream
import java.security.KeyStore
import java.{util => ju}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.util.Timeout
import com.softwaremill.macwire._
import io.prometheus.client._
import javax.net.ssl._
import net.ripe.rpki.publicationserver.metrics._
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


object Boot extends App {
  lazy val conf = wire[AppConfig]
  lazy val logger = setupLogging(conf)

  logger.info("Starting up the publication server ...")

  PgStore.migrateDB(conf.pgConfig)
  new PublicationServerApp(conf, logger).run()

  def setupLogging(conf: AppConfig) = {
//    System.setProperty("LOG_FILE", conf.locationLogfile)
//    SysStreamsLogger.bindSystemStreams()
    LoggerFactory.getLogger(this.getClass)
  }
}

class PublicationServerApp(conf: AppConfig, logger: Logger) extends RRDPService {
    
  implicit val system = ActorSystem.create(Math.abs(new ju.Random().nextLong()).toString())
  implicit val dispatcher = system.dispatcher

  var httpBinding: Future[ServerBinding] = _
  var httpsBinding: Future[ServerBinding] = _

  val healthChecks = new HealthChecks(conf)

  def run() {

    implicit val timeout = Timeout(5.seconds)

    val registry = CollectorRegistry.defaultRegistry
    val metrics = Metrics.get(registry)
    val metricsApi = new MetricsApi(registry)

    val publicationService = new PublicationService(conf, metrics)

    logger.info("Server address " + conf.serverAddress)

    this.httpsBinding = Http().bindAndHandle(
      publicationService.publicationRoutes,
      interface = conf.serverAddress,
      port = conf.publicationPort,
      connectionContext = ConnectionContext.https(sslContext()),
      settings = conf.publicationServerSettings.get      
    )

    this.httpBinding = Http().bindAndHandle(
      rrdpAndMonitoringRoutes ~ metricsApi.routes,
      interface = conf.serverAddress,
      port = conf.rrdpPort
    )

    println(httpBinding.value)
  }

  def shutdown() = {
       Await.result(httpBinding, 10.seconds)            
            .terminate(hardDeadline = 3.seconds)
            .flatMap(_ => 
                Await.result(httpsBinding, 10.seconds)            
                .terminate(hardDeadline = 3.seconds))
            .flatMap(_ => system.terminate)
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
