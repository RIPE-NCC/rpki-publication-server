package net.ripe.rpki.publicationserver.model

import java.util.UUID

import net.ripe.rpki.publicationserver.{Hash, PublicationServerBaseTest}
import org.scalatest._

class NotificationTest extends FunSuite with BeforeAndAfter with Matchers {

  test("should serialize a Notification to proper xml") {
    val snapshot = SnapshotLocator("rsync://bla", Hash("2a3s4v"))
    val deltas = Seq(DeltaLocator(BigInt(987), "rsync://deltabla", Hash("1234sg")))

    val sessionId = UUID.randomUUID()
    val notification = Notification(sessionId, BigInt(234), snapshot, deltas)

    val xml = notification.serialize.mkString

    trim(xml) should be(trim(s"""<notification version="1" session_id="${sessionId.toString}" serial="234" xmlns="http://www.ripe.net/rpki/rrdp">
                                  <snapshot uri="rsync://bla" hash="2a3s4v"/>
                                  <delta serial="987" uri="rsync://deltabla" hash="1234sg"/>
                                </notification>"""))
  }

  def trim(s: String): String = s.filterNot(_.isWhitespace)
}
