package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver.{Base64, Hash, PublicationServerBaseSpec}
import slick.driver.H2Driver

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ObjectStoreSpec extends PublicationServerBaseSpec {

  val objectStore = new ObjectStore
  val db = objectStore.inMemory

  before {
    Migrations.migrate(db)
    objectStore.clear()
  }

  test("should store an object and extract it") {
    val clientId = ClientId("client1")
    val i = objectStore.insertAction(clientId, (Base64("AAAA=="), Hash("jfkfhjghj"), new URI("rsync://host.com/path")))
    Await.result(db.run(i), Duration.Inf)
    objectStore.list(clientId) should be(Vector((Base64("AAAA=="), Hash("jfkfhjghj"), new URI("rsync://host.com/path"))))
  }

  test("should store an object, withdraw it and make sure it's not there anymore") {
    val clientId = ClientId("client1")
    val hash = Hash("98623986923")
    val i = objectStore.insertAction(clientId, (Base64("AAAA=="), hash, new URI("rsync://host.com/another_path")))
    Await.result(db.run(i), Duration.Inf)

    objectStore.list(clientId) should be(Vector((Base64("AAAA=="), hash, new URI("rsync://host.com/another_path"))))

    val d = objectStore.deleteAction(clientId, hash)
    Await.result(db.run(d), Duration.Inf)

    objectStore.list(clientId) should be(Vector())
  }

}
