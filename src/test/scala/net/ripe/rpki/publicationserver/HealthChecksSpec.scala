package net.ripe.rpki.publicationserver

import org.scalatest.{FunSuite, Matchers}
import spray.json._

class HealthChecksSpec extends FunSuite with Matchers with TestUtils {
  val healthChecks = new HealthChecks()

  test("should return build info in json format") {
    val buildInfo = healthChecks.healthString.parseJson.asJsObject

    buildInfo.fields.keySet should contain("buildNumber")
    buildInfo.fields.keySet should contain("buildTimestamp")
    buildInfo.fields.keySet should contain("revisionNumber")
    buildInfo.fields.keySet should contain("host")
  }
}
