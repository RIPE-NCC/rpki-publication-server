package net.ripe.rpki.publicationserver

import java.net.InetAddress

import net.ripe.rpki.publicationserver.store.ServerStateStore
import spray.json._

import scala.util.Try

class HealthChecks extends Config {

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

  lazy val serverStateStore = new ServerStateStore

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
    val result = Try(serverStateStore.get)
    if (result.isFailure) result.failed.get.getMessage else "OK"        // TODO throw error or so
  }

  def mb(b: Long) = (b/(1024*1024)) + "mb"

  def memoryStat = {
    val r = Runtime.getRuntime
    Memory(free = mb(r.freeMemory), total = mb(r.totalMemory), max = mb(r.maxMemory))
  }
}