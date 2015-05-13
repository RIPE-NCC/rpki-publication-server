package net.ripe.rpki.publicationserver

import spray.json._

class HealthChecksSpec extends PublicationServerBaseSpec {
  val healthChecks = new HealthChecks()

  test("should return build info in json format") {
    val buildInfo = healthChecks.healthString.parseJson.asJsObject

    buildInfo.fields.keySet should contain("buildNumber")
    buildInfo.fields.keySet should contain("buildTimestamp")
    buildInfo.fields.keySet should contain("revisionNumber")
    buildInfo.fields.keySet should contain("host")
  }
}
