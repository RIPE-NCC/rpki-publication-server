package net.ripe.rpki.publicationserver

import java.util.UUID
import java.net.URI

class SnapshotStateSpec extends PublicationServerBaseSpec {

  private var sessionId: UUID = _

  private var emptySnapshot: SnapshotState = _
  
  before {
    sessionId = UUID.randomUUID
    emptySnapshot = new SnapshotState(sessionId, BigInt(1), Map.empty)
  }
  
  test("should serialize a SnapshotState to proper xml") {
    val pdus: Map[URI, (Base64, Hash)] = Map(new URI("rsync://bla") -> (Base64.apply("321"), Hash("123")))
    val state = new SnapshotState(sessionId, BigInt(123), pdus)

    val xml = state.serialize.mkString

    trim(xml) should be(trim(s"""<snapshot version="1" session_id="${sessionId.toString}" serial="123" xmlns="HTTP://www.ripe.net/rpki/rrdp">
                                  <publish uri="rsync://bla" hash="123">321</publish>
                                </snapshot>"""))
  }

  test("should add an object with publish") {
    val s = emptySnapshot(Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))
    s._2.get.serial should be(BigInt(2))
    s._2.get.sessionId should be(sessionId)
    s._2.get.pdus should be(Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))
  }

  test("should update an object with publish and republish") {
    val snapshot = new SnapshotState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"),
      tag = None,
      hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
      base64 = Base64("cccc="))))

    s._2.get.serial should be(BigInt(2))
    s._2.get.sessionId should be(sessionId)
    s._2.get.pdus should be(Map(new URI("rsync://host/zzz.cer") -> (Base64("cccc="), Hash("5DEC005081ED747F172993860AACDD6492B2547BE0EC440CED76649F65188E14"))))
  }

  test("should fail to update an object which is not in the snashot") {
    val snapshot = new SnapshotState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(PublishQ(
      uri = new URI("rsync://host/not-existing.cer"),
      tag = None,
      hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
      base64 = Base64("cccc="))))

    s._1.head should be(ReportError(MsgError.NoObjectToUpdate, Some("No object [rsync://host/not-existing.cer] has been found.")))
  }

  test("should fail to update an object without hash provided") {
    val snapshot = new SnapshotState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("cccc="))))
    s._1.head should be(ReportError(MsgError.HashForInsert, Some("Tried to insert existing object [rsync://host/zzz.cer].")))
  }

  test("should fail to update an object if hashes do not match") {
    val snapshot = new SnapshotState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(PublishQ(
      uri = new URI("rsync://host/zzz.cer"),
      tag = None,
      hash = Some("WRONGHASH"),
      base64 = Base64("cccc="))))

    s._1.head should be(ReportError(MsgError.NonMatchingHash, Some("Cannot republish the object [rsync://host/zzz.cer], hash doesn't match")))
  }

  test("should fail to withdraw an object if there's no such object") {
    val snapshot = new SnapshotState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(WithdrawQ(uri = new URI("rsync://host/not-existing-uri.cer"), tag = None, hash = "whatever")))
    s._1.head should be(ReportError(MsgError.NoObjectForWithdraw, Some("No object [rsync://host/not-existing-uri.cer] found.")))
  }

  test("should fail to withdraw an object if hashes do not match") {
    val snapshot = new SnapshotState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))))

    val s = snapshot(Seq(WithdrawQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = "WRONGHASH")))
    s._1.head should be(ReportError(MsgError.NonMatchingHash, Some("Cannot withdraw the object [rsync://host/zzz.cer], hash doesn't match.")))
  }

}
