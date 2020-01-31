import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.PublicationServerBaseTest
import net.ripe.rpki.publicationserver.model.{ServerState, Snapshot}

class SnapshotTest extends PublicationServerBaseTest {

  test("should serialize to proper xml") {
    val sessionId = UUID.randomUUID()
    val bytes = Bytes(Array(0x010, 0x12, 0x23))
    val state = Snapshot(ServerState(sessionId, 123L), Seq((bytes, new URI("rsync://bla"))))

    state.bytes should be(toBytes(
      s"""<snapshot version="1" session_id="$sessionId" serial="123" xmlns="http://www.ripe.net/rpki/rrdp">\n<publish uri="rsync://bla">${Bytes.toBase64(bytes).value}</publish>\n</snapshot>"""))
  }

  private def toBytes(s: String) = {
    s.map(c => c.toByte).toArray
  }
}
