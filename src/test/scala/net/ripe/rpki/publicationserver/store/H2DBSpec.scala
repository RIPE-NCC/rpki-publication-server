package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver.{Base64, Hash, PublicationServerBaseSpec}

class H2DBSpec extends PublicationServerBaseSpec {

  val h2db = new H2DB

  before {
    Migrations.migrate(h2db.db)
  }

  test("should store an object and extract it") {
    val clientId = ClientId("client1")
    h2db.publish(clientId, (Base64("AAAA=="), Hash("jfkfhjghj"), new URI("rsync://host.com/path")))
    h2db.list(clientId) should be(Vector((Base64("AAAA=="), Hash("jfkfhjghj"), new URI("rsync://host.com/path"))))
  }

}
