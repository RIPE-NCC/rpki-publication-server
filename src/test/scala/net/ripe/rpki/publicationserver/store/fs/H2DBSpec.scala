package net.ripe.rpki.publicationserver.store.fs

import java.net.URI

import net.ripe.rpki.publicationserver.store.{H2DB, ClientId}
import net.ripe.rpki.publicationserver.{Hash, Base64, PublicationServerBaseSpec}

class H2DBSpec extends PublicationServerBaseSpec {

  test("should store an object and extract it") {
    val h2db = new H2DB
    val clientId = ClientId("client1")
    h2db.publish(clientId, (Base64("AAAA=="), Hash("jfkfhjghj"), new URI("")))
    h2db.list(clientId) should be(Seq((Base64("AAAA=="), Hash("jfkfhjghj"), new URI(""))))
  }

}
