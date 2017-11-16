package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.store.ObjectStore
import org.mockito.Mockito._
import spray.json._

class HealthChecksTest extends PublicationServerBaseTest {

  val theObjectStore = mock[ObjectStore](RETURNS_SMART_NULLS)

  val healthChecks = new HealthChecks() {
    override val objectStore = theObjectStore
  }

  test("should return build info and database connectivity in json format") {
    val buildInfo = healthChecks.healthString.parseJson.asJsObject

    buildInfo.fields.keySet should contain("buildInformation")
    buildInfo.fields.keySet should contain("databaseConnectivity")
    buildInfo.fields("databaseConnectivity") should equal(JsString("OK"))
  }

  test("should throw exception with error message for database connectivity") {
    when(theObjectStore.check).thenThrow(new RuntimeException("Cannot connect!"))

    val thrown = intercept[RuntimeException] {
      healthChecks.healthString
    }

    thrown.getMessage should equal("Cannot connect!")
  }
}
