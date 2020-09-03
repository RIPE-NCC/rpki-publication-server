package net.ripe.rpki.publicationserver.model

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.{PublicationServerBaseTest, PublishQ, WithdrawQ}
import org.scalatest._

class DeltaTest extends FunSuite with BeforeAndAfter with Matchers {

  test("should serialize to proper xml") {
    val sessionId = UUID.randomUUID()
    val bytes1 = Bytes(Array(0x10, 0x20, 0x30))
    val bytes2 = Bytes(Array(0x76, 0x54, 0x65, 0x55, 0x44, 0x33))
    val publishQ1 = PublishQ(new URI("rsync://bla.replace"), None, Some("AABBCCEE"), bytes1)
    val publishQ2 = PublishQ(new URI("rsync://bla.add"), None, None, bytes2)
    val withdrawQ = WithdrawQ(new URI("rsync://bla.delete"), None, "AABB")
    val state = Delta(sessionId, 123L, Seq(publishQ1, withdrawQ, publishQ2), None)

    val xml = "<delta version=\"1\" session_id=\"" + sessionId + "\" serial=\"123\" xmlns=\"http://www.ripe.net/rpki/rrdp\">\n" +
      "<publish uri=\"rsync://bla.replace\" hash=\"AABBCCEE\">" + Bytes.toBase64(bytes1).value + "</publish>\n" +
      "<withdraw uri=\"rsync://bla.delete\" hash=\"AABB\"/>\n" +
      "<publish uri=\"rsync://bla.add\">" + Bytes.toBase64(bytes2).value + "</publish>\n" +
      "</delta>"
//    state.bytes should be(toBytes(xml))
  }

  private def toBytes(s: String) =
    s.map(c => c.toByte).toArray
  
}