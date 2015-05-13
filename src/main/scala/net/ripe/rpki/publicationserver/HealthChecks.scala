package net.ripe.rpki.publicationserver

import java.net.InetAddress

import spray.json._

class HealthChecks {

  case class BuildInformation(buildNumber: String, buildTimestamp: String, revisionNumber: String, host: String)
  object BuildInformation

  object HealthChecksJsonProtocol extends DefaultJsonProtocol {
    implicit val buildInformationFormat = jsonFormat4(BuildInformation.apply)
  }

  import HealthChecksJsonProtocol._

  def healthString: String = {
    val buildInformation = BuildInformation(
      buildNumber = systemProperty("build.number"),
      buildTimestamp = systemProperty("build.timestamp"),
      revisionNumber = systemProperty("revision.number"),
      host = InetAddress.getLocalHost.getHostName
    )
    buildInformation.toJson.prettyPrint
  }

  private def systemProperty(key: String) = sys.props.getOrElse(key, "dev")
}