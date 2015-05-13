package net.ripe.rpki.publicationserver

import org.scalatest.{FunSuite, Matchers}

class HealthChecksSpec extends FunSuite with Matchers with TestUtils {
  val healthChecks = new HealthChecks()

  test("should return build info in json format") {
    trim(healthChecks.healthString) should be(trim("""
    {
      "buildNumber": "dev",
      "buildTimestamp": "dev",
      "revisionNumber": "dev",
      "host": "guest7.guestnet.ripe.net"
    }"""))
  }
}
