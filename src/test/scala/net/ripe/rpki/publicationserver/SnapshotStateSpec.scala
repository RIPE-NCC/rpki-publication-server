package net.ripe.rpki.publicationserver

class SnapshotStateSpec extends PublicationServerBaseSpec {

  test("should serialize a SnapshotState to proper xml") {
    val pdus: Map[String, (Base64, Hash)] = Map("rsync://bla" -> (Base64.apply("321"), Hash("123")))
    val state = new SnapshotState(SessionId("s123"), BigInt(123), pdus)

    val xml = SnapshotState.serialize(state).mkString

    trim(xml) should be(trim("""<snapshot version="1" session_id="s123" serial="123" xmlns="HTTP://www.ripe.net/rpki/rrdp">
                                  <publish uri="rsync://bla" hash="123">321</publish>
                                </snapshot>"""))
  }

  private val emptySnapshot = new SnapshotState(SessionId("session1"), BigInt(1), Map.empty)

  test("should add an object with publish") {
    val s = emptySnapshot(Seq(PublishQ(uri = "rsync://host/zzz.cer", tag = None, hash = None, base64 = Base64("aaaa="))))
    s.right.get.serial should be(BigInt(2))
    s.right.get.sessionId should be(SessionId("session1"))
    s.right.get.pdus should be(Map("rsync://host/zzz.cer" -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))
  }

  test("should update an object with publish and republish") {
    val snapshot = new SnapshotState(
      SessionId("session1"),
      BigInt(1),
      Map("rsync://host/zzz.cer" -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(PublishQ(uri = "rsync://host/zzz.cer",
      tag = None,
      hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
      base64 = Base64("cccc="))))

    s.right.get.serial should be(BigInt(2))
    s.right.get.sessionId should be(SessionId("session1"))
    s.right.get.pdus should be(Map("rsync://host/zzz.cer" -> (Base64("cccc="), Hash("5DEC005081ED747F172993860AACDD6492B2547BE0EC440CED76649F65188E14"))))
  }

  test("should fail to update an object which is not in the snashot") {
    val snapshot = new SnapshotState(
      SessionId("session1"),
      BigInt(1),
      Map("rsync://host/zzz.cer" -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(PublishQ(
      uri = "rsync://host/not-existing.cer",
      tag = None,
      hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
      base64 = Base64("cccc="))))

    s.left.get should be(MsgError(MsgError.NoObjectToUpdate, "No object [rsync://host/not-existing.cer] has been found."))
  }

  test("should fail to update an object without hash provided") {
    val snapshot = new SnapshotState(
      SessionId("session1"),
      BigInt(1),
      Map("rsync://host/zzz.cer" -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(PublishQ(uri = "rsync://host/zzz.cer", tag = None, hash = None, base64 = Base64("cccc="))))
    s.left.get should be(MsgError(MsgError.HashForInsert, "Tried to insert existing object [rsync://host/zzz.cer]."))
  }

  test("should fail to update an object if hashes do not match") {
    val snapshot = new SnapshotState(
      SessionId("session1"),
      BigInt(1),
      Map("rsync://host/zzz.cer" -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(PublishQ(
      uri = "rsync://host/zzz.cer",
      tag = None,
      hash = Some("WRONGHASH"),
      base64 = Base64("cccc="))))

    s.left.get should be(MsgError(MsgError.NonMatchingHash, "Cannot republish the object [rsync://host/zzz.cer], hash doesn't match"))
  }

  test("should fail to withdraw an object if there's no such object") {
    val snapshot = new SnapshotState(
      SessionId("session1"),
      BigInt(1),
      Map("rsync://host/zzz.cer" -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(WithdrawQ(uri = "rsync://host/not-existing-uri.cer", tag = None, hash = "whatever")))
    s.left.get should be(MsgError(MsgError.NoObjectForWithdraw, "No object [rsync://host/not-existing-uri.cer] found."))
  }

  test("should fail to withdraw an object if hashes do not match") {
    val snapshot = new SnapshotState(
      SessionId("session1"),
      BigInt(1),
      Map("rsync://host/zzz.cer" -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(WithdrawQ(uri = "rsync://host/zzz.cer", tag = None, hash = "WRONGHASH")))
    s.left.get should be(MsgError(MsgError.NonMatchingHash, "Cannot withdraw the object [rsync://host/zzz.cer], hash doesn't match."))
  }

}
