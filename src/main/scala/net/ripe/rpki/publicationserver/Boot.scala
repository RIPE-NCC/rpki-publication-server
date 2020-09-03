package net.ripe.rpki.publicationserver

import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, SupervisorStrategy}
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import com.softwaremill.macwire._
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import net.ripe.rpki.publicationserver.store.XodusDB
import net.ripe.rpki.publicationserver.metrics._
import org.slf4j.{Logger, LoggerFactory}
import io.prometheus.client._

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future
import akka.http.scaladsl.Http.ServerBinding
import java.{util => ju}

import net.ripe.rpki.publicationserver.util.SSLHelper

import scala.util.{Failure, Success, Try}



object Boot extends App {
  lazy val conf = wire[AppConfig]
  lazy val logger = setupLogging(conf)

  override final def main(args: Array[String]) = {
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

    new PublicationServerApp(conf, https, logger).run()
  }

  def setupLogging(conf: AppConfig) = {
    System.setProperty("LOG_FILE", conf.locationLogfile)
    LoggerFactory.getLogger(this.getClass)
  }
}

class PublicationServerApp(conf: AppConfig, https: ConnectionContext, logger: Logger) extends RRDPService {
    
  implicit val system = ActorSystem.create(Math.abs(new ju.Random().nextLong()).toString())
  implicit val dispatcher = system.dispatcher

  var httpBinding: Future[ServerBinding] = _
  var httpsBinding: Future[ServerBinding] = _

  def run() {      
    XodusDB.reset()
    XodusDB.init(conf.storePath)    

    logger.info("Starting up the publication server ...")    
    logger.info("Server address " + conf.serverAddress)

    implicit val timeout = Timeout(5.seconds)

    val registry = CollectorRegistry.defaultRegistry
    val metrics = Metrics.get(registry)
    val metricsApi = new MetricsApi(registry)

    val stateActor: ActorRef = system.actorOf(StateActor.props(conf, metrics))

    val publicationService = new PublicationService(conf, stateActor)

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
            .flatMap(_ => system.terminate)
  }
}
