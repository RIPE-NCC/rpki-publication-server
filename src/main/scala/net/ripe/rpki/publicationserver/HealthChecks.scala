package net.ripe.rpki.publicationserver

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete

import java.net.InetAddress
import net.ripe.rpki.publicationserver.store.postgresql.PgStore
import spray.json._

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.util.Try

class HealthChecks(val appConfig: AppConfig) extends Logging {

  case class BuildInformation(buildTimestamp: String, commit: String, host: String, memory : Memory)
  object BuildInformation

  case class Memory(free: String, total: String, max: String)
  object Memory

  case class Health(buildInformation: BuildInformation, databaseConnectivity: String)
  object Health

  case class SnapshotStatus(ready: Boolean, objectsCount: Int)
  object SnapshotStatus

  object HealthChecksJsonProtocol extends DefaultJsonProtocol {
    implicit val memoryFormat = jsonFormat3(Memory.apply)
    implicit val buildInformationFormat = jsonFormat4(BuildInformation.apply)
    implicit val snapshotFormat = jsonFormat2(SnapshotStatus.apply)
    implicit val healthFormat = jsonFormat2(Health.apply)
  }

  lazy val objectStore = PgStore.get(appConfig.pgConfig)

  val snapshotObjectCounts = new AtomicInteger(0)

  import HealthChecksJsonProtocol._

  def healthString: String = {
    val buildInformation = BuildInformation(
      buildTimestamp = GeneratedBuildInformation.buildDate,
      commit = GeneratedBuildInformation.commit,
      host = InetAddress.getLocalHost.getHostName,
      memory = memoryStat
    )
    val health = Health(buildInformation, checkDatabaseStatus)

    health.toJson.prettyPrint
  }

  def checkDatabaseStatus: String = {
    val result = Try(objectStore.check())
    if (result.isFailure) throw result.failed.get else "OK"
  }

  def mb(b: Long) = s"${(b/(1024*1024))}mb"

  def memoryStat = {
    val r = Runtime.getRuntime
    Memory(free = mb(r.freeMemory), total = mb(r.totalMemory), max = mb(r.maxMemory))
  }

  def updateSnapshot(objectsCount: Int) = {
    snapshotObjectCounts.set(objectsCount)
  }

  def snapshotStatus(): SnapshotStatus = {
    val current = snapshotObjectCounts.get()
    SnapshotStatus(current >= appConfig.minimumSnapshotObjectsCount, current)
  }

  def readinessResponse = {
    val currentStatus = snapshotStatus()
    if(currentStatus.ready)
      complete(currentStatus.toJson.prettyPrint)
    else {
      logger.info(s"Service is not ready, snapshot object counts ${snapshotObjectCounts}")
      complete(StatusCodes.ServiceUnavailable)
    }
  }
}
