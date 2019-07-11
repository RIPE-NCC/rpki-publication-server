package net.ripe.rpki.publicationserver

import java.io.FileInputStream
import java.security.KeyStore

import akka.actor.ActorSystem
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.softwaremill.macwire.MacwireMacros._
import javax.net.ssl._
import net.ripe.logging.SysStreamsLogger
import net.ripe.rpki.publicationserver.store.XodusDB
import org.slf4j.LoggerFactory
import spray.can.Http
import spray.io.ServerSSLEngineProvider

import scala.concurrent.duration._

object Boot extends App {

  lazy val conf = wire[AppConfig]

  XodusDB.init()

  val logger = setupLogging()
  logger.info("Starting up the publication server ...")

  implicit val system = ActorSystem("0")

  val publicationService = system.actorOf(PublicationServiceActor.props(conf), "publication-service")
  val rrdpService = system.actorOf(RRDPServiceActor.props(), "rrdp-service")

  implicit val timeout = Timeout(5.seconds)

  implicit val sslContext: SSLContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(getKeyManagers, getTrustManagers, null)
    sslContext
  }

  val publicationSslEngineProvider = ServerSSLEngineProvider { sslEngine =>
    sslEngine.setWantClientAuth(conf.publicationServerTrustStoreLocation.nonEmpty)
    sslEngine
  }

  IO(Http) ? Http.Bind(publicationService,
      interface = "::0",
      port = conf.publicationPort,
      settings = conf.publicationServerSettings)(publicationSslEngineProvider)

  IO(Http) ? Http.Bind(rrdpService, interface = "::0", port = conf.rrdpPort)

  def setupLogging() = {
    System.setProperty("LOG_FILE", conf.locationLogfile)
    SysStreamsLogger.bindSystemStreams()
    LoggerFactory.getLogger(this.getClass)
  }

  def getTrustManagers: Array[TrustManager] = {
    if (conf.publicationServerTrustStoreLocation.isEmpty) {
      logger.info("publication.server.truststore.location is not set, skipping truststore init")
      null
    } else {
      val trustStore = KeyStore.getInstance("JKS")
      val tsPassword: Array[Char] = if (conf.publicationServerTrustStorePassword.isEmpty) null
      else conf.publicationServerTrustStorePassword.toCharArray
      logger.info(s"Loading HTTPS certificate from ${conf.publicationServerTrustStoreLocation}")
      trustStore.load(new FileInputStream(conf.publicationServerTrustStoreLocation), tsPassword)
      val tmf = TrustManagerFactory.getInstance("SunX509")
      tmf.init(trustStore)
      tmf.getTrustManagers
    }
  }

  def getKeyManagers: Array[KeyManager] = {
    if (conf.publicationServerKeyStoreLocation.isEmpty) {
      if (conf.getConfig.getBoolean("publication.spray.can.server.ssl-encryption")) {
        logger.error("publication.spray.can.server.ssl-encryption is ON, but publication.server.keystore.location " +
            "is not defined. THIS WILL NOT WORK!")
      } else {
        logger.info("publication.server.keystore.location is not set, skipping keystore init")
      }
      null
    } else {
      val ksPassword = if (conf.publicationServerKeyStorePassword.isEmpty) null
      else conf.publicationServerKeyStorePassword.toCharArray
      val keyStore = KeyStore.getInstance("JKS")
      logger.info(s"Loading HTTPS certificate from ${conf.publicationServerKeyStoreLocation}")
      keyStore.load(new FileInputStream(conf.publicationServerKeyStoreLocation), ksPassword)
      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(keyStore, ksPassword)
      kmf.getKeyManagers
    }
  }
}
