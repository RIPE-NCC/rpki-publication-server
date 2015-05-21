package net.ripe.rpki.publicationserver

class SnapshotStateSpec extends PublicationServerBaseSpec {

  test("should serialize a SnapshotState to proper xml") {
    val state = SnapshotState(SessionId("s123"), BigInt(123))

    val xml = SnapshotState.serialize(state).mkString

    trim(xml) should be(trim("""<delta version="1" session_id="s123" serial="123" xmlns="HTTP://www.ripe.net/rpki/rrdp">
                           </delta>"""))
  }
}
