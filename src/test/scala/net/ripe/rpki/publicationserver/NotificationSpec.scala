package net.ripe.rpki.publicationserver

class NotificationSpec extends PublicationServerBaseSpec {

  test("should serialize a Notification to proper xml") {
    val snapshot = SnapshotLocator("rsync://bla", Hash("2a3s4v"))
    val deltas = Seq(Delta(BigInt(987), "rsync://deltabla", Hash("1234sg")))

    val notification = Notification(SessionId("s123"), BigInt(234), snapshot, deltas)

    val xml = notification.serialize.mkString

    trim(xml) should be(trim("""<notification version="1" session_id="s123" serial="234" xmlns="HTTP://www.ripe.net/rpki/rrdp">
                                  <snapshot uri="rsync://bla" hash="2a3s4v"/>
                                  <delta serial="987" uri="rsync://deltabla" hash="1234sg"/>
                                </notification>"""))
  }
}
