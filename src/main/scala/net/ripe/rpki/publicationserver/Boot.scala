package net.ripe.rpki.publicationserver

import java.{util => ju}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.util.Timeout
import com.softwaremill.macwire._
import io.prometheus.client._
import net.ripe.rpki.publicationserver.metrics._
import net.ripe.rpki.publicationserver.store.postgresql.PgStore
import net.ripe.rpki.publicationserver.util.SSLHelper
import org.slf4j.Logger

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import net.ripe.rpki.publicationserver.repository.DataFlusher
import akka.actor.Cancellable

import scala.util.control.NonFatal


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

class PublicationServerApp(conf: AppConfig, https: HttpsConnectionContext, logger: Logger) extends MonitoringService {

  implicit val system = ActorSystem.create(Math.abs(new ju.Random().nextLong()).toString)
  implicit val dispatcher = system.dispatcher

  var httpBinding: Future[ServerBinding] = _
  var httpsBinding: Future[ServerBinding] = _
  var repositoryWriter: Future[Cancellable] = _

  implicit val healthChecks = new HealthChecks(conf)

  def run(): Unit = {

    implicit val timeout = Timeout(5.seconds)

    val registry = CollectorRegistry.defaultRegistry
    val metrics = Metrics.get(registry)
    val metricsApi = new MetricsApi(registry)

    // Initialize repositories on FS and setup writing at a fixed interval.
    // Run it asynchronously as it can be quite long, but we want
    // to start accepting HTTP(S) connections ASAP.
    this.repositoryWriter = Future {
      val dataFlusher = new DataFlusher(conf)
      dataFlusher.initFS()
      val interval = conf.repositoryFlushInterval
      logger.info(s"Scheduling FS update every ${interval}")
      system.scheduler.scheduleAtFixedRate(
        interval,
        interval
      ) (dataFlusher.updateFS _)
    }.recover {
      case NonFatal(e) =>
        logger.error("Failed to start repository writer", e)
        Cancellable.alreadyCancelled
    }

    val publicationService = new PublicationService(conf, metrics)

    logger.info("Server address: " + conf.serverAddress)

    // TODO Catch binding errors
    this.httpsBinding = Http()
      .newServerAt(conf.serverAddress, conf.publicationPort)
      .enableHttps(https)
      .withSettings(conf.publicationServerSettings.get)
      .bindFlow(publicationService.publicationRoutes)

    this.httpBinding = Http()
      .newServerAt(conf.serverAddress, conf.rrdpPort)
      .bindFlow(monitoringRoutes ~ metricsApi.routes)

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
    Await.ready(repositoryWriter, Duration.Inf)
  }

  def shutdown() = {
      Await.result(repositoryWriter, 3.seconds).cancel()
      Await.result(httpBinding, 10.seconds)
           .terminate(hardDeadline = 3.seconds)
           .flatMap(_ =>
                Await.result(httpsBinding, 10.seconds)
                     .terminate(hardDeadline = 3.seconds))
           .flatMap(_ => system.terminate())
  }
}
