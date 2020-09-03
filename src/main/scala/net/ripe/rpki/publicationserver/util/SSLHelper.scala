package net.ripe.rpki.publicationserver.util

import java.io.FileInputStream
import java.security.KeyStore

import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import javax.net.ssl._
import net.ripe.rpki.publicationserver.AppConfig
import org.slf4j.Logger

import scala.util.Try

class SSLHelper(conf: AppConfig, logger: Logger) {
  def connectionContext: Try[HttpsConnectionContext] = {
    val sslContext = Try(SSLContext.getInstance("TLS"))
    sslContext.foreach(_.init(getKeyManagers, getTrustManagers, null))
    sslContext.flatMap(ctx => Try(ConnectionContext.https(ctx)))
  }

  private def getTrustManagers: Array[TrustManager] = {
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
