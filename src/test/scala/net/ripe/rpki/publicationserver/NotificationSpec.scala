package net.ripe.rpki.publicationserver

import java.util.UUID

class NotificationSpec extends PublicationServerBaseSpec {

  test("should serialize a Notification to proper xml") {
    val snapshot = SnapshotLocator("rsync://bla", Hash("2a3s4v"))
    val deltas = Seq(Delta(BigInt(987), "rsync://deltabla", Hash("1234sg")))

    val sessionId = UUID.randomUUID()
    val notification = Notification(sessionId, BigInt(234), snapshot, deltas)

    val xml = notification.serialize.mkString

    trim(xml) should be(trim(s"""<notification version="1" session_id="${sessionId.toString}" serial="234" xmlns="HTTP://www.ripe.net/rpki/rrdp">
                                  <snapshot uri="rsync://bla" hash="2a3s4v"/>
                                  <delta serial="987" uri="rsync://deltabla" hash="1234sg"/>
                                </notification>"""))
  }
}
