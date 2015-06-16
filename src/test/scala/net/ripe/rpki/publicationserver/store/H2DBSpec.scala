package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver.{Base64, Hash, PublicationServerBaseSpec}

class H2DBSpec extends PublicationServerBaseSpec {

  val objectStore = new ObjectStore

  before {
    Migrations.migrate(objectStore.db)
    objectStore.clear()
  }

  test("should store an object and extract it") {
    val clientId = ClientId("client1")
    objectStore.publish(clientId, (Base64("AAAA=="), Hash("jfkfhjghj"), new URI("rsync://host.com/path")))
    objectStore.list(clientId) should be(Vector((Base64("AAAA=="), Hash("jfkfhjghj"), new URI("rsync://host.com/path"))))
  }

  test("should store an object, withdraw it and make sure it's not there anymore") {
    val clientId = ClientId("client1")
    val hash = Hash("98623986923")
    objectStore.publish(clientId, (Base64("AAAA=="), hash, new URI("rsync://host.com/another_path")))
    objectStore.list(clientId) should be(Vector((Base64("AAAA=="), hash, new URI("rsync://host.com/another_path"))))

    objectStore.withdraw(clientId, hash)
    objectStore.list(clientId) should be(Vector())
  }

}
