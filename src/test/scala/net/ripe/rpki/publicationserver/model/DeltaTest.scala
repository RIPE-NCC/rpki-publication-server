package net.ripe.rpki.publicationserver.model

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.{Base64, PublicationServerBaseTest, PublishQ, WithdrawQ}

class DeltaTest extends PublicationServerBaseTest {

  test("should serialize to proper xml") {
    val sessionId = UUID.randomUUID()
    val publishQ1 = PublishQ(new URI("rsync://bla.replace"), None, Some("AABBCCEE"), Base64("321"))
    val publishQ2 = PublishQ(new URI("rsync://bla.add"), None, None, Base64("765432319"))
    val withdrawQ = WithdrawQ(new URI("rsync://bla.delete"), None, "AABB")
    val state = Delta(sessionId, 123L, Seq(publishQ1, withdrawQ, publishQ2), None)

    val xml = "<delta version=\"1\" session_id=\"" + sessionId + "\" serial=\"123\" xmlns=\"http://www.ripe.net/rpki/rrdp\">" +
      "<publish uri=\"rsync://bla.replace\" hash=\"AABBCCEE\">321</publish>" +
      "<withdraw uri=\"rsync://bla.delete\" hash=\"AABB=\"/>" +
      "<publish uri=\"rsync://bla.add\">765432319</publish>" +
      "</delta>"
    state.bytes should be(toBytes(xml))
  }

  private def toBytes(s: String) = {
    s.map(c => c.toByte).toArray
  }
}