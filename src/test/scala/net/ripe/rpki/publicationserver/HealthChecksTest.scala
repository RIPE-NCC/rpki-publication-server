package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.store.ServerStateStore
import org.mockito.Mockito._
import spray.json._

class HealthChecksTest extends PublicationServerBaseTest {

  val serverStateDb = mock[ServerStateStore](RETURNS_SMART_NULLS)

  val healthChecks = new HealthChecks(){
    override lazy val serverStateStore = serverStateDb
  }

  test("should return build info and database connectivity in json format") {
    val buildInfo = healthChecks.healthString.parseJson.asJsObject

    buildInfo.fields.keySet should contain("buildInformation")
    buildInfo.fields.keySet should contain("databaseConnectivity")
    buildInfo.fields.get("databaseConnectivity").get should equal(JsString("OK"))
  }

  test("should throw exception with error message for database connectivity") {
    when(serverStateDb.get).thenThrow(new RuntimeException("Cannot connect!"))

    val thrown = intercept[RuntimeException] {
      healthChecks.healthString
    }

    thrown.getMessage should equal("Cannot connect!")
  }
}
