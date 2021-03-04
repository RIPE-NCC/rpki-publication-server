package net.ripe.rpki.publicationserver

import java.{util => ju}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.util.Timeout
import com.softwaremill.macwire._
import io.prometheus.client._
import net.ripe.rpki.publicationserver.metrics._
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import net.ripe.rpki.publicationserver.util.SSLHelper
import org.slf4j.Logger

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}


object Boot extends App with Logging {
  lazy val conf = wire[AppConfig]

  logger.info("Starting up the publication server ...")

  val sslHelper = new SSLHelper(conf, logger)
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
  logger.info("Migrated the DB")
  new PublicationServerApp(conf, https, logger).run()
}

class PublicationServerApp(conf: AppConfig, https: ConnectionContext, logger: Logger) extends RRDPService {
    
  implicit val system = ActorSystem.create(Math.abs(new ju.Random().nextLong()).toString)
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

    // Run it asynchronously as it can be quite long, but we want
    // to start accepting HTTP(S) connections ASAP.
    val asyncLongFSInit = Future { publicationService.initFS() }

    logger.info("Server address: " + conf.serverAddress)

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

    httpsBinding.onComplete {
      case Failure(e) =>
        logger.error("Problem binding to HTTPS, exiting", e)
        System.exit(1)
      case Success(_) => ()
    }
    httpBinding.onComplete {
      case Failure(e) =>
        logger.error("Problem binding to HTTP, exiting", e)
        System.exit(1)
      case Success(_) => ()
    }

    // wait for the full FS sync
    Await.ready(asyncLongFSInit, Duration.Inf)
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
