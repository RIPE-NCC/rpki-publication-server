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
import scala.concurrent.Await
import scala.concurrent.Future
import akka.http.scaladsl.Http.ServerBinding
import java.{util => ju}




object Boot extends App with Logging {
  lazy val conf = wire[AppConfig]

  override final def main(args: Array[String]) = {
    val sslHelper = new SSLHelper(conf, logger)

    logger.info("Starting up the publication server ...")

    val https: HttpsConnectionContext = {
      sslHelper.connectionContext match {
        case Success(v) => v
        case Failure(e) =>
          logger.error("Error while creating SSL context, exiting", e)
          // EX_DATAERR (65) The input data was incorrect in some way.
          sys.exit(65)
      }
    }

    PgStore.migrateDB(conf.pgConfig)
    new PublicationServerApp(conf, https, logger).run()
  }
}

class PublicationServerApp(conf: AppConfig, https: ConnectionContext, logger: Logger) extends RRDPService {
    
  implicit val system = ActorSystem.create(Math.abs(new ju.Random().nextLong()).toString())
  implicit val dispatcher = system.dispatcher

  var httpBinding: Future[ServerBinding] = _
  var httpsBinding: Future[ServerBinding] = _

  val healthChecks = new HealthChecks(conf)

  def run(): Unit = {

    implicit val timeout = Timeout(5.seconds)

    val registry = CollectorRegistry.defaultRegistry
    val metrics = Metrics.get(registry)
    val metricsApi = new MetricsApi(registry)

    val publicationService = new PublicationService(conf, metrics)

    logger.info("Server address " + conf.serverAddress)

    // TODO Catch binding errors
    this.httpsBinding = Http().bindAndHandle(
      publicationService.publicationRoutes,
      interface = conf.serverAddress,
      port = conf.publicationPort,
      connectionContext = https,
      settings = conf.publicationServerSettings.get      
    )

    this.httpBinding = Http().bindAndHandle(
      rrdpAndMonitoringRoutes ~ metricsApi.routes,
      interface = conf.serverAddress,
      port = conf.rrdpPort
    )
  }

  def shutdown() = {
       Await.result(httpBinding, 10.seconds)            
            .terminate(hardDeadline = 3.seconds)
            .flatMap(_ => 
                Await.result(httpsBinding, 10.seconds)            
                .terminate(hardDeadline = 3.seconds))
            .flatMap(_ => system.terminate())
  }
}
