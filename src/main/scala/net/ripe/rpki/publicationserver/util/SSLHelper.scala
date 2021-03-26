package net.ripe.rpki.publicationserver.util

import java.io.FileInputStream
import java.security.{KeyStore, SecureRandom}

import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import javax.net.ssl._
import net.ripe.rpki.publicationserver.AppConfig
import org.slf4j.Logger

import scala.util.Try

class SSLHelper(conf: AppConfig, logger: Logger) {
  /**
   * Initialise a SSLContext using the settings from the config.
   *
   * This requires that both the truststore and keystore files are present and have valid passwords.
   *
   * @return a Success containing a valid SSL context, a Failure otherwise
   */
  def connectionContext: Try[HttpsConnectionContext] = {
    Try(SSLContext.getInstance("TLS"))
      .map(ctx => {
        ctx.init(getKeyManagers.getKeyManagers, getTrustManagers.getTrustManagers, new SecureRandom)
        ConnectionContext.httpsServer(ctx)
      })
  }

  private def getTrustManagers: TrustManagerFactory = {
    require(!conf.publicationServerTrustStorePassword.isEmpty, "server.truststore.password is empty")
    logger.info(s"Loading HTTPS certificate from ${conf.publicationServerTrustStoreLocation}")

    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(new FileInputStream(conf.publicationServerTrustStoreLocation), conf.publicationServerTrustStorePassword.toCharArray)
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(trustStore)
    tmf
  }

  private def getKeyManagers: KeyManagerFactory = {
    require(!conf.publicationServerKeyStorePassword.isEmpty, "server.keystore.password is empty")
    logger.info(s"Loading HTTPS certificate from ${conf.publicationServerKeyStoreLocation}")

    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(new FileInputStream(conf.publicationServerKeyStoreLocation), conf.publicationServerKeyStorePassword.toCharArray)
    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(keyStore, conf.publicationServerKeyStorePassword.toCharArray)
    kmf
  }
}
