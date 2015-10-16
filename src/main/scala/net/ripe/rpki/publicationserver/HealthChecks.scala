package net.ripe.rpki.publicationserver

import java.net.InetAddress

import spray.json._

class HealthChecks {

  case class BuildInformation(buildNumber: String, buildTimestamp: String, revisionNumber: String, host: String, memory : Memory)
  object BuildInformation

  case class Memory(free: String, total: String, max: String)
  object Memory

  object HealthChecksJsonProtocol extends DefaultJsonProtocol {
    implicit val memoryFormat = jsonFormat3(Memory.apply)
    implicit val buildInformationFormat = jsonFormat5(BuildInformation.apply)
  }

  import HealthChecksJsonProtocol._

  def healthString: String = {
    val buildInformation = BuildInformation(
      buildNumber = GeneratedBuildInformation.version,
      buildTimestamp = GeneratedBuildInformation.buildDate,
      revisionNumber = GeneratedBuildInformation.revision,
      host = InetAddress.getLocalHost.getHostName,
      memory = memoryStat
    )
    buildInformation.toJson.prettyPrint
  }

  def mb(b: Long) = (b/(1024*1024)) + "mb"

  def memoryStat = {
    val r = Runtime.getRuntime
    Memory(free = mb(r.freeMemory), total = mb(r.totalMemory), max = mb(r.maxMemory))
  }
}