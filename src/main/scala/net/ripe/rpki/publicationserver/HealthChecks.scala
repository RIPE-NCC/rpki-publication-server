package net.ripe.rpki.publicationserver

import java.net.InetAddress

import net.ripe.rpki.publicationserver.store.postresql.PgStore
import spray.json._

import scala.util.Try

class HealthChecks(val appConfig: AppConfig) {

  case class BuildInformation(buildNumber: String, buildTimestamp: String, revisionNumber: String, host: String, memory : Memory)
  object BuildInformation

  case class Memory(free: String, total: String, max: String)
  object Memory

  case class Health(buildInformation: BuildInformation, databaseConnectivity: String)
  object Health

  object HealthChecksJsonProtocol extends DefaultJsonProtocol {
    implicit val memoryFormat = jsonFormat3(Memory.apply)
    implicit val buildInformationFormat = jsonFormat5(BuildInformation.apply)
    implicit val healthFormat = jsonFormat2(Health.apply)
  }

  lazy val objectStore = PgStore.get(appConfig.pgConfig)

  import HealthChecksJsonProtocol._

  def healthString: String = {
    val buildInformation = BuildInformation(
      buildNumber = GeneratedBuildInformation.version,
      buildTimestamp = GeneratedBuildInformation.buildDate,
      revisionNumber = GeneratedBuildInformation.revision,
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
}