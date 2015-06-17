package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Paths, Path}
import java.util.UUID

import net.ripe.rpki.publicationserver.store.ClientId
import net.ripe.rpki.publicationserver.store.fs.RepositoryWriter
import org.mockito.Matchers._
import org.mockito.Mockito._

class RepositoryStateSpec extends PublicationServerBaseSpec with Urls {

  private var sessionId: UUID = _

  private var emptySnapshot: ChangeSet = _
  
  before {
    sessionId = UUID.randomUUID
    emptySnapshot = new ChangeSet(sessionId, BigInt(1), Map.empty)
  }
  
  test("should serialize a SnapshotState to proper xml") {
    val pdus: Map[URI, (Base64, Hash)] = Map(new URI("rsync://bla") -> (Base64.apply("321"), Hash("123")))
    val state = new ChangeSet(sessionId, BigInt(123), Map.empty)

//    val xml = state.serialize.mkString
//
//    trim(xml) should be(trim(s"""<snapshot version="1" session_id="${sessionId.toString}" serial="123" xmlns="HTTP://www.ripe.net/rpki/rrdp">
//                                  <publish uri="rsync://bla" hash="123">321</publish>
//                                </snapshot>"""))
  }

  test("should add an object with publish") {
    val s = emptySnapshot.next(Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))
    s.serial should be(BigInt(2))
    s.sessionId should be(sessionId)
    s.deltas should be(Map(
      BigInt(2) -> Delta(
        sessionId,
        BigInt(2),
        Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa=")))
      )
    ))
  }

  test("should update an object with publish and republish") {
    val snapshot = new ChangeSet(
      sessionId,
      BigInt(1),
      Map.empty)

    val s = snapshot.next(Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"),
      tag = None,
      hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
      base64 = Base64("cccc="))))

    s.serial should be(BigInt(2))
    s.sessionId should be(sessionId)
    s.deltas should be(Map(
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
    val snapshot = new ChangeSet(
      sessionId,
      BigInt(1),
      Map.empty)

    val s = snapshot.next(Seq(PublishQ(
      uri = new URI("rsync://host/not-existing.cer"),
      tag = None,
      hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
      base64 = Base64("cccc="))))

//    s.head should be(ReportError(BaseError.NoObjectToUpdate, Some("No object [rsync://host/not-existing.cer] has been found.")))
  }

  test("should fail to update an object without hash provided") {
    val snapshot = new ChangeSet(
      sessionId,
      BigInt(1),
      Map.empty)

    val s = snapshot.next(Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("cccc="))))
//    s.head should be(ReportError(BaseError.HashForInsert, Some("Tried to insert existing object [rsync://host/zzz.cer].")))
  }

  test("should fail to update an object if hashes do not match") {
    val snapshot = new ChangeSet(
      sessionId,
      BigInt(1),
      Map.empty)

    val s = snapshot.next(Seq(PublishQ(
      uri = new URI("rsync://host/zzz.cer"),
      tag = None,
      hash = Some("WRONGHASH"),
      base64 = Base64("cccc="))))

//    s._1.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot republish the object [rsync://host/zzz.cer], hash doesn't match")))
  }

  test("should fail to withdraw an object if there's no such object") {
    val snapshot = new ChangeSet(
      sessionId,
      BigInt(1),
      Map.empty)

    val s = snapshot.next(Seq(WithdrawQ(uri = new URI("rsync://host/not-existing-uri.cer"), tag = None, hash = "whatever")))
//    s._1.head should be(ReportError(BaseError.NoObjectForWithdraw, Some("No object [rsync://host/not-existing-uri.cer] found.")))
  }

  test("should fail to withdraw an object if hashes do not match") {
    val snapshot = new ChangeSet(
      sessionId,
      BigInt(1),
      Map.empty)

    val s = snapshot.next(Seq(WithdrawQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = "WRONGHASH")))
//    s._1.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot withdraw the object [rsync://host/zzz.cer], hash doesn't match.")))
  }

  test("should create 2 entries in delta map after 2 updates") {
    val s1 = emptySnapshot.next(Seq(PublishQ(uri = new URI("rsync://host/cert1.cer"), tag = None, hash = None, base64 = Base64("cccc="))))
    val s2 = s1.next(Seq(PublishQ(uri = new URI("rsync://host/cert2.cer"), tag = None, hash = None, base64 = Base64("bbbb="))))

    s2.deltas should be(Map(
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

    snapshotStateUpdater.updateWith(ClientId("client1"), Seq(publish))

    verify(repositoryWriterSpy).writeNewState(anyString(), any[ChangeSet], any[Notification], "")
    snapshotStateUpdater.get should not equal snapshotStateBefore
    notificationStateSpy.get should not equal notificationStateBefore
  }

  def getNotificationUpdater: NotificationStateUpdater = {
    val notificationStateUpdater = new NotificationStateUpdater()
    notificationStateUpdater.update(Notification.create("", emptySnapshot))
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
    snapshotStateUpdater.updateWith(ClientId("client1"), Seq(withdraw))

    verifyNoMoreInteractions(repositoryWriterSpy)
  }

  test("should not update the snapshot state when writing it to the filesystem throws an error") {
    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationUpdater
    doThrow(new IllegalArgumentException()).when(repositoryWriterSpy).writeSnapshot(anyString(), any[ChangeSet], "")

    val snapshotStateUpdater = new SnapshotStateUpdater {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val stateBefore = snapshotStateUpdater.get

    val reply = snapshotStateUpdater.updateWith(ClientId("client1"), Seq(publish))
    reply.tail should equal(Seq(ReportError(BaseError.CouldNotPersist, Some("Could not persist the changes: null"))))
    verify(repositoryWriterSpy).deleteSnapshot(anyString(), any[ChangeSet])
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

    val reply = snapshotStateUpdater.updateWith(ClientId("client1"), Seq(publish))
    reply.tail should equal(Seq(ReportError(BaseError.CouldNotPersist, Some("Could not persist the changes: null"))))
    verify(repositoryWriterSpy).deleteSnapshot(anyString(), any[ChangeSet])
    verify(repositoryWriterSpy).deleteDelta(anyString(), any[ChangeSet])
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

    val reply = snapshotStateUpdater.updateWith(ClientId("client1"), Seq(publish))
    reply.tail should equal(Seq(ReportError(BaseError.CouldNotPersist, Some("Could not persist the changes: null"))))
    verify(repositoryWriterSpy).deleteSnapshot(anyString(), any[ChangeSet])
    verify(repositoryWriterSpy).deleteDelta(anyString(), any[ChangeSet])
    verify(repositoryWriterSpy).deleteNotification(anyString(), any[ChangeSet])
    snapshotStateUpdater.get should equal(snapshotStateBefore)
    notificationStateSpy.get should equal(notificationStateBefore)
  }
  
  def getRepositoryWriter: RepositoryWriter = new MockRepositoryWriter()

  class MockRepositoryWriter extends RepositoryWriter {
    override def writeSnapshot(rootDir: String, snapshot: ChangeSet, snapshoXml: String): Unit = { }
    override def writeDelta(rootDir: String, delta: Delta): Unit = { }
    override def writeNotification(rootDir: String, notification: Notification): Path = Paths.get("")
  }

}
