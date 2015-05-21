package net.ripe.rpki.publicationserver

class SnapshotStateSpec extends PublicationServerBaseSpec {

  test("should serialize a SnapshotState to proper xml") {
    val pdus: Map[String, (Base64, Hash)] = Map("rsync://bla" -> (Base64.apply("321"), Hash("123")))
    val state = SnapshotState(SessionId("s123"), BigInt(123), pdus)

    val xml = SnapshotState.serialize(state).mkString

    trim(xml) should be(trim("""<delta version="1" session_id="s123" serial="123" xmlns="HTTP://www.ripe.net/rpki/rrdp">
                                  <publish uri="rsync://bla" hash="123">321</publish>
                                </delta>"""))
  }
}
