package net.ripe.rpki.publicationserver

import java.net.URI

import net.ripe.rpki.publicationserver.store.DB.ServerState

class SnapshotSpec extends PublicationServerBaseSpec {

  test("should serialize to proper xml") {
    val sessionId = "1234ab"
    val state = Snapshot(ServerState(sessionId, 123L), Seq((Base64("321"), Hash("123"), new URI("rsync://bla"))))

    val xml = state.serialize.mkString

    trim(xml) should be(trim(s"""<snapshot version="1" session_id="$sessionId" serial="123" xmlns="HTTP://www.ripe.net/rpki/rrdp">
                                  <publish uri="rsync://bla" hash="123">321</publish>
                                </snapshot>"""))
  }
}
