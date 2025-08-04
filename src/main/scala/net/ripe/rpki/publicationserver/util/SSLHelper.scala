package net.ripe.rpki.publicationserver.util

import java.io.FileInputStream
import java.security.{KeyStore, SecureRandom}

import org.apache.pekko.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
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
  def connectionContext: Try[HttpsConnectionContext] = Try {
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(loadKeyManagers(), loadTrustManagers(), new SecureRandom)
    ConnectionContext.httpsServer(() => {
      val engine = ctx.createSSLEngine()
      engine.setUseClientMode(false)
      engine.setNeedClientAuth(true)
      engine.setEnabledProtocols(Array("TLSv1.2", "TLSv1.3"))
      engine
    })
  }

  private def loadTrustManagers(): Array[TrustManager] = {
    require(!conf.publicationServerTrustStorePassword.isEmpty, "server.truststore.password is empty")
    logger.info(s"Loading HTTPS certificate from ${conf.publicationServerTrustStoreLocation}")

    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(new FileInputStream(conf.publicationServerTrustStoreLocation), conf.publicationServerTrustStorePassword.toCharArray)
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(trustStore)
    tmf.getTrustManagers
  }

  private def loadKeyManagers(): Array[KeyManager] = {
    require(!conf.publicationServerKeyStorePassword.isEmpty, "server.keystore.password is empty")
    logger.info(s"Loading HTTPS certificate from ${conf.publicationServerKeyStoreLocation}")

    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(new FileInputStream(conf.publicationServerKeyStoreLocation), conf.publicationServerKeyStorePassword.toCharArray)
    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(keyStore, conf.publicationServerKeyStorePassword.toCharArray)
    kmf.getKeyManagers
  }
}
