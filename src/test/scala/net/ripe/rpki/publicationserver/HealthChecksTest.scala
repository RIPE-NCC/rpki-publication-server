package net.ripe.rpki.publicationserver

import spray.json._

class HealthChecksTest extends PublicationServerBaseTest {
  val healthChecks = new HealthChecks()

  test("should return build info in json format") {
    val buildInfo = healthChecks.healthString.parseJson.asJsObject

    buildInfo.fields.keySet should contain("buildNumber")
    buildInfo.fields.keySet should contain("buildTimestamp")
    buildInfo.fields.keySet should contain("revisionNumber")
    buildInfo.fields.keySet should contain("host")
  }
}
