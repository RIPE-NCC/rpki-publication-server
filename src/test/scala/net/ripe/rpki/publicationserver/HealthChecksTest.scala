package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.store.postresql.PgStore
import org.mockito.Mockito._
import spray.json._

class HealthChecksTest extends PublicationServerBaseTest {

  test("should return build info and database connectivity in json format") {
    val thePgStore = PgStore.get(pgTestConfig)
    val healthChecks = new HealthChecks(null) {
      override lazy val objectStore = thePgStore
    }

    val buildInfo = healthChecks.healthString.parseJson.asJsObject

    buildInfo.fields.keySet should contain("buildInformation")
    buildInfo.fields.keySet should contain("databaseConnectivity")
    buildInfo.fields("databaseConnectivity") should equal(JsString("OK"))
  }

  test("should throw exception with error message for database connectivity") {
    val healthChecks = new HealthChecks(null) {
      override lazy val objectStore = new PgStore(pgTestConfig) {
        override def check() = throw new RuntimeException("Cannot connect!")
      }
    }

    val thrown = intercept[RuntimeException] {
      healthChecks.healthString
    }
    thrown.getMessage should equal("Cannot connect!")
  }
}
