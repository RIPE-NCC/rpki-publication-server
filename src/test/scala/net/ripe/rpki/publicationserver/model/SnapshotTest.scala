import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.model.{ServerState, Snapshot}
import net.ripe.rpki.publicationserver.{Base64, PublicationServerBaseTest}

class SnapshotTest extends PublicationServerBaseTest {

  test("should serialize to proper xml") {
    val sessionId = UUID.randomUUID()
    val state = Snapshot(ServerState(sessionId, 123L), Seq((Base64("321"), new URI("rsync://bla"))))

    state.bytes should be(toBytes(
      s"""<snapshot version="1" session_id="$sessionId" serial="123" xmlns="http://www.ripe.net/rpki/rrdp"><publish uri="rsync://bla">321</publish></snapshot>"""))
  }

  private def toBytes(s: String) = {
    s.map(c => c.toByte).toArray
  }
}
