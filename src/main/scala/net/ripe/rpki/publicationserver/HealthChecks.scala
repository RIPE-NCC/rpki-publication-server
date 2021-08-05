package net.ripe.rpki.publicationserver

import java.net.InetAddress

import net.ripe.rpki.publicationserver.store.postgresql.PgStore
import spray.json._

import scala.util.Try

class HealthChecks(val appConfig: AppConfig) {

  case class BuildInformation(buildTimestamp: String, commit: String, host: String, memory : Memory)
  object BuildInformation

  case class Memory(free: String, total: String, max: String)
  object Memory

  case class Health(buildInformation: BuildInformation, databaseConnectivity: String)
  object Health

  case class SnapshotStatus(ready: Boolean, size: Long, objectsCount: Int)
  object SnapshotStatus

  object HealthChecksJsonProtocol extends DefaultJsonProtocol {
    implicit val memoryFormat = jsonFormat3(Memory.apply)
    implicit val buildInformationFormat = jsonFormat4(BuildInformation.apply)
    implicit val snapshotFormat = jsonFormat3(SnapshotStatus.apply)
    implicit val healthFormat = jsonFormat2(Health.apply)
  }

  lazy val objectStore = PgStore.get(appConfig.pgConfig)

  var snapshotStatus = SnapshotStatus(false, 0, 0)

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

  def readinessString: String = {

    snapshotStatus.toJson.prettyPrint
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

  def updateSnapshot(snapshotSize: Long, objectsCount: Int) = {
    snapshotStatus = SnapshotStatus(snapshotSize > appConfig.minimumSnapshotSize && objectsCount > appConfig.minimumSnapshotObjectsCount, snapshotSize, objectsCount)
  }
}
