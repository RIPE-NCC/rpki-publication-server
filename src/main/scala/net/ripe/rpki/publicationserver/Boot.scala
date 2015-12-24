package net.ripe.rpki.publicationserver

import java.io.{FileInputStream, PrintStream}
import java.security.KeyStore
import javax.net.ssl.{TrustManager, KeyManager, KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.logging.LoggingOutputStream
import net.ripe.rpki.publicationserver.store.fs.FSWriterActor
import org.apache.log4j.{Level, Logger}
import org.slf4j.LoggerFactory
import spray.can.Http
import spray.io.ServerSSLEngineProvider

import scala.concurrent.duration._

object Boot extends App {

  lazy val conf = wire[AppConfig]

  val logger = setupLogging()
  logger.info("Starting up the publication server ...")

  implicit val system = ActorSystem("publication-rrdp-server")

  val fsWriterFactory = (context:ActorRefFactory) => context.actorOf(FSWriterActor.props())

  val publicationService = system.actorOf(PublicationServiceActor.props(fsWriterFactory), "publication-service")
  val rrdpService = system.actorOf(RRDPServiceActor.props(), "rrdp-service")

  implicit val timeout = Timeout(5.seconds)

  implicit val sslContext: SSLContext = {
    val sslContext = SSLContext.getInstance("TLS")

    val keyManagers: Array[KeyManager] = {
      if (conf.publicationServerKeyStoreLocation.isEmpty) null
      else {
        val ksPassword = if (conf.publicationServerKeyStorePassword.isEmpty) null
        else conf.publicationServerKeyStorePassword.toCharArray
        val keyStore = KeyStore.getInstance("JKS")
        keyStore.load(new FileInputStream(conf.publicationServerKeyStoreLocation), ksPassword)
        val kmf = KeyManagerFactory.getInstance("SunX509")
        kmf.init(keyStore, ksPassword)
        kmf.getKeyManagers
      }
    }

    val trustManagers: Array[TrustManager] = {
      if (conf.publicationServerTrustStoreLocation.isEmpty) null
      else {
        val trustStore = KeyStore.getInstance("JKS")
        val tsPassword: Array[Char] = if (conf.publicationServerTrustStorePassword.isEmpty) null
                                      else conf.publicationServerTrustStorePassword.toCharArray
        trustStore.load(new FileInputStream(conf.publicationServerTrustStoreLocation), tsPassword)
        val tmf = TrustManagerFactory.getInstance("SunX509")
        tmf.init(trustStore)
        tmf.getTrustManagers
      }
    }

    sslContext.init(keyManagers, trustManagers, null)
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
    System.setOut(new PrintStream(new LoggingOutputStream(Logger.getRootLogger, Level.INFO), true))
    System.setErr(new PrintStream(new LoggingOutputStream(Logger.getRootLogger, Level.ERROR), true))
    LoggerFactory.getLogger(this.getClass)
  }
}
