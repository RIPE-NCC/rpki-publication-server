package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Paths, Path}
import java.util.UUID

import net.ripe.rpki.publicationserver.fs.RepositoryWriter
import org.mockito.Matchers._
import org.mockito.Mockito._

class RepositoryStateSpec extends PublicationServerBaseSpec with Urls {

  private var sessionId: UUID = _

  private var emptySnapshot: RepositoryState = _
  
  before {
    sessionId = UUID.randomUUID
    emptySnapshot = new RepositoryState(sessionId, BigInt(1), Map.empty, Map.empty)
  }
  
  test("should serialize a SnapshotState to proper xml") {
    val pdus: Map[URI, (Base64, Hash)] = Map(new URI("rsync://bla") -> (Base64.apply("321"), Hash("123")))
    val state = new RepositoryState(sessionId, BigInt(123), pdus, Map.empty)

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
    s._2.get.deltas should be(Map(
      BigInt(2) -> Delta(
        sessionId,
        BigInt(2),
        Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa=")))
      )
    ))
  }

  test("should update an object with publish and republish") {
    val snapshot = new RepositoryState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))),
      Map.empty)

    val s = snapshot(Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"),
      tag = None,
      hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
      base64 = Base64("cccc="))))

    s._2.get.serial should be(BigInt(2))
    s._2.get.sessionId should be(sessionId)
    s._2.get.pdus should be(Map(new URI("rsync://host/zzz.cer") -> (Base64("cccc="), Hash("5DEC005081ED747F172993860AACDD6492B2547BE0EC440CED76649F65188E14"))))
    s._2.get.deltas should be(Map(
      BigInt(2) -> Delta(
        sessionId,
        BigInt(2),
        Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"),
          tag = None,
          hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
          base64 = Base64("cccc=")))
      )
    ))
  }

  test("should fail to update an object which is not in the snashot") {
    val snapshot = new RepositoryState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))),
      Map.empty)

    val s = snapshot(Seq(PublishQ(
      uri = new URI("rsync://host/not-existing.cer"),
      tag = None,
      hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
      base64 = Base64("cccc="))))

    s._1.head should be(ReportError(BaseError.NoObjectToUpdate, Some("No object [rsync://host/not-existing.cer] has been found.")))
  }

  test("should fail to update an object without hash provided") {
    val snapshot = new RepositoryState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))),
      Map.empty)

    val s = snapshot(Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("cccc="))))
    s._1.head should be(ReportError(BaseError.HashForInsert, Some("Tried to insert existing object [rsync://host/zzz.cer].")))
  }

  test("should fail to update an object if hashes do not match") {
    val snapshot = new RepositoryState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))),
      Map.empty)

    val s = snapshot(Seq(PublishQ(
      uri = new URI("rsync://host/zzz.cer"),
      tag = None,
      hash = Some("WRONGHASH"),
      base64 = Base64("cccc="))))

    s._1.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot republish the object [rsync://host/zzz.cer], hash doesn't match")))
  }

  test("should fail to withdraw an object if there's no such object") {
    val snapshot = new RepositoryState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))),
      Map.empty)

    val s = snapshot(Seq(WithdrawQ(uri = new URI("rsync://host/not-existing-uri.cer"), tag = None, hash = "whatever")))
    s._1.head should be(ReportError(BaseError.NoObjectForWithdraw, Some("No object [rsync://host/not-existing-uri.cer] found.")))
  }

  test("should fail to withdraw an object if hashes do not match") {
    val snapshot = new RepositoryState(
      sessionId,
      BigInt(1),
      Map(new URI("rsync://host/zzz.cer") -> (Base64("aaaa="), Hash("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"))),
      Map.empty)

    val s = snapshot(Seq(WithdrawQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = "WRONGHASH")))
    s._1.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot withdraw the object [rsync://host/zzz.cer], hash doesn't match.")))
  }

  test("should create 2 entries in delta map after 2 updates") {
    val s1 = emptySnapshot(Seq(PublishQ(uri = new URI("rsync://host/cert1.cer"), tag = None, hash = None, base64 = Base64("cccc="))))
    val s2 = s1._2.get(Seq(PublishQ(uri = new URI("rsync://host/cert2.cer"), tag = None, hash = None, base64 = Base64("bbbb="))))

    s2._2.get.deltas should be(Map(
      BigInt(2) -> Delta(
        sessionId,
        BigInt(2),
        Seq(PublishQ(uri = new URI("rsync://host/cert1.cer"), tag = None, hash = None, base64 = Base64("cccc=")))
      ),
      BigInt(3) -> Delta(
        sessionId,
        BigInt(3),
        Seq(PublishQ(uri = new URI("rsync://host/cert2.cer"), tag = None, hash = None, base64 = Base64("bbbb=")))
      )
    ))
  }

  test("should update the snapshot and the notification and write them to the filesystem when a message is successfully processed") {
    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationUpdater
    val snapshotStateUpdater = new SnapshotStateUpdater {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val snapshotStateBefore = snapshotStateUpdater.get
    val notificationStateBefore = notificationStateSpy.get

    snapshotStateUpdater.updateWith(Seq(publish))

    verify(repositoryWriterSpy).writeNewState(anyString(), any[RepositoryState], any[Notification])
    snapshotStateUpdater.get should not equal snapshotStateBefore
    notificationStateSpy.get should not equal notificationStateBefore
  }

  def getNotificationUpdater: NotificationStateUpdater = {
    val notificationStateUpdater = new NotificationStateUpdater()
    notificationStateUpdater.update(Notification.create(emptySnapshot))
    notificationStateUpdater
  }

  test("should not write a snapshot to the filesystem when a message contained an error") {
    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationUpdater
    val snapshotStateUpdater = new SnapshotStateUpdater {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val withdraw = WithdrawQ(new URI("rsync://host/zzz.cer"), None, "BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7")

    // The withdraw will fail because the SnapshotState is still empty
    snapshotStateUpdater.updateWith(Seq(withdraw))

    verifyNoMoreInteractions(repositoryWriterSpy)
  }

  test("should not update the snapshot state when writing it to the filesystem throws an error") {
    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationUpdater
    doThrow(new IllegalArgumentException()).when(repositoryWriterSpy).writeSnapshot(anyString(), any[RepositoryState])

    val snapshotStateUpdater = new SnapshotStateUpdater {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val stateBefore = snapshotStateUpdater.get

    val reply = snapshotStateUpdater.updateWith(Seq(publish))
    reply.tail should equal(Seq(ReportError(BaseError.CouldNotPersist, Some("Could not persist the changes: null"))))
    verify(repositoryWriterSpy).deleteSnapshot(anyString(), any[RepositoryState])
    snapshotStateUpdater.get should equal(stateBefore)
  }

  test("should not update the snapshot, delta and notification state when updating delta throws an error") {
    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationUpdater
    doThrow(new IllegalArgumentException()).when(repositoryWriterSpy).writeDelta(anyString(), any[Delta])

    val snapshotStateUpdater = new SnapshotStateUpdater {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val snapshotStateBefore = snapshotStateUpdater.get
    val notificationStateBefore = notificationStateSpy.get

    val reply = snapshotStateUpdater.updateWith(Seq(publish))
    reply.tail should equal(Seq(ReportError(BaseError.CouldNotPersist, Some("Could not persist the changes: null"))))
    verify(repositoryWriterSpy).deleteSnapshot(anyString(), any[RepositoryState])
    verify(repositoryWriterSpy).deleteDelta(anyString(), any[RepositoryState])
    snapshotStateUpdater.get should equal(snapshotStateBefore)
    notificationStateSpy.get should equal(notificationStateBefore)
  }

  test("should not update the snapshot, delta and notification state when updating the notification throws an error") {
    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationUpdater
    doThrow(new IllegalArgumentException()).when(repositoryWriterSpy).writeNotification(anyString(), any[Notification])

    val snapshotStateUpdater = new SnapshotStateUpdater {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val snapshotStateBefore = snapshotStateUpdater.get
    val notificationStateBefore = notificationStateSpy.get

    val reply = snapshotStateUpdater.updateWith(Seq(publish))
    reply.tail should equal(Seq(ReportError(BaseError.CouldNotPersist, Some("Could not persist the changes: null"))))
    verify(repositoryWriterSpy).deleteSnapshot(anyString(), any[RepositoryState])
    verify(repositoryWriterSpy).deleteDelta(anyString(), any[RepositoryState])
    verify(repositoryWriterSpy).deleteNotification(anyString(), any[RepositoryState])
    snapshotStateUpdater.get should equal(snapshotStateBefore)
    notificationStateSpy.get should equal(notificationStateBefore)
  }
  
  def getRepositoryWriter: RepositoryWriter = new MockRepositoryWriter()

  class MockRepositoryWriter extends RepositoryWriter {
    override def writeSnapshot(rootDir: String, snapshot: RepositoryState): Unit = { }
    override def writeDelta(rootDir: String, delta: Delta): Unit = { }
    override def writeNotification(rootDir: String, notification: Notification): Path = Paths.get("")
  }

}
