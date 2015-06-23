package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.UUID

import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.fs.RepositoryWriter
import net.ripe.rpki.publicationserver.store.{ObjectStore, ServerStateStore, DeltaStore, Migrations}
import org.mockito.Matchers._
import org.mockito.Mockito._

class SnapshotStateTest extends PublicationServerBaseTest with Urls {

  private var serial: Long = _

  private var sessionId: UUID = _

  private val deltaStore = new DeltaStore

  private val serverStateStore = new ServerStateStore

  private val objectStore = new ObjectStore

  objectStore.clear()
  SnapshotState.deltaStore.clear()

  before {
    serial = 1L
    sessionId = conf.currentSessionId
    deltaStore.clear()
    serverStateStore.clear()
    Migrations.initServerState()
  }

  test("should write the snapshot and delta's from the db to the filesystem on init") {
    val mockRepositoryWriter = spy(new RepositoryWriter)
    val mockDeltaStore = spy(new DeltaStore)

    val snapshotState = new SnapshotStateService {
      override val repositoryWriter = mockRepositoryWriter
      override lazy val deltaStore = mockDeltaStore
    }
    when(mockDeltaStore.getDeltas).thenReturn(Seq(Delta(sessionId, 1L, Seq.empty)))

    snapshotState.init(sessionId)

    verify(mockDeltaStore).initCache(sessionId)
    verify(mockRepositoryWriter).writeSnapshot(any[String], any[ServerState], any[Snapshot])
    verify(mockRepositoryWriter).writeDelta(any[String], any[Delta])
  }

  test("should add an object with publish") {
    SnapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))

    SnapshotState.deltaStore.getDeltas should be(Seq(
      Delta(
        sessionId,
        2L,
        Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa=")))
      )
    ))
  }

  test("should update an object with publish and republish") {

    val replies = SnapshotState.updateWith(
      ClientId("bla"),
      Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"),
        tag = None,
        hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
        base64 = Base64("cccc="))))


    SnapshotState.deltaStore.getDeltas should be(Seq(
      Delta(
        sessionId,
        2L,
        Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"),
          tag = None,
          hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
          base64 = Base64("cccc=")))
      )
    ))
  }

  test("should fail to update an object which is not in the snapshot") {
    val replies = SnapshotState.updateWith(
      ClientId("bla"),
      Seq(PublishQ(
        uri = new URI("rsync://host/not-existing.cer"),
        tag = None,
        hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
        base64 = Base64("cccc="))))

    replies.head should be(ReportError(BaseError.NoObjectToUpdate, Some("No object [rsync://host/not-existing.cer] has been found.")))
  }

  test("should fail to update an object without hash provided") {
    val replies = SnapshotState.updateWith(
      ClientId("bla"),Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("cccc="))))

    replies.head should be(ReportError(BaseError.HashForInsert, Some("Tried to insert existing object [rsync://host/zzz.cer].")))
  }

  test("should fail to update an object if hashes do not match") {
    val replies = SnapshotState.updateWith(
      ClientId("bla"),
      Seq(PublishQ(
      uri = new URI("rsync://host/zzz.cer"),
      tag = None,
      hash = Some("WRONGHASH"),
      base64 = Base64("cccc="))))

    replies.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot republish the object [rsync://host/zzz.cer], hash doesn't match")))
  }

  test("should fail to withdraw an object if there's no such object") {
    val replies = SnapshotState.updateWith(
      ClientId("bla"),Seq(WithdrawQ(uri = new URI("rsync://host/not-existing-uri.cer"), tag = None, hash = "whatever")))

    replies.head should be(ReportError(BaseError.NoObjectForWithdraw, Some("No object [rsync://host/not-existing-uri.cer] found.")))
  }

  test("should fail to withdraw an object if hashes do not match") {
    val replies = SnapshotState.updateWith(
      ClientId("bla"),Seq(WithdrawQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = "WRONGHASH")))

    replies.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot withdraw the object [rsync://host/zzz.cer], hash doesn't match.")))
  }

  test("should create 2 entries in delta map after 2 updates") {
    SnapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/cert1.cer"), tag = None, hash = None, base64 = Base64("cccc="))))

    SnapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/cert2.cer"), tag = None, hash = None, base64 = Base64("bbbb="))))

    SnapshotState.deltaStore.getDeltas should be(Seq(
      Delta(
        sessionId,
        2L,
        Seq(PublishQ(uri = new URI("rsync://host/cert1.cer"), tag = None, hash = None, base64 = Base64("cccc=")))
      ),
      Delta(
        sessionId,
        3L,
        Seq(PublishQ(uri = new URI("rsync://host/cert2.cer"), tag = None, hash = None, base64 = Base64("bbbb=")))
      )
    ))
  }

  test("should update the snapshot and the notification and write them to the filesystem when a message is successfully processed") {
    SnapshotState.objectStore.clear()

    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationState
    val snapshotStateService = new SnapshotStateService {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val notificationStateBefore = notificationStateSpy.get

    snapshotStateService.updateWith(ClientId("client1"), Seq(publish))

    verify(repositoryWriterSpy).writeNewState(anyString(), any[ServerState], any[Seq[Delta]], any[Notification], any[Snapshot])
    notificationStateSpy.get should not equal notificationStateBefore
  }

  def getNotificationState: NotificationState = {
    val notificationState = new NotificationState()
    notificationState.update(Notification.create(Snapshot(ServerState(sessionId, serial), Seq.empty), ServerState(sessionId, serial), Seq()))
    notificationState
  }

  test("should not write a snapshot to the filesystem when a message contained an error") {
    SnapshotState.objectStore.clear()

    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationState
    val snapshotStateService = new SnapshotStateService {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val withdraw = WithdrawQ(new URI("rsync://host/zzz.cer"), None, "BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7")

    // The withdraw will fail because the SnapshotState is still empty
    snapshotStateService.updateWith(ClientId("client1"), Seq(withdraw))

    verifyNoMoreInteractions(repositoryWriterSpy)
  }

  test("should not update the snapshot state when writing it to the filesystem throws an error") {
    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationState
    doThrow(new IllegalArgumentException()).when(repositoryWriterSpy).writeSnapshot(anyString(), any[ServerState], any[Snapshot])

    val snapshotStateService = new SnapshotStateService {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))

    val reply = snapshotStateService.updateWith(ClientId("client1"), Seq(publish))
    reply.tail should equal(Seq(ReportError(BaseError.CouldNotPersist, Some("Could not write XML files to filesystem: null"))))
    verify(repositoryWriterSpy).deleteSnapshot(anyString(), any[ServerState])
  }

  test("should not update the notification state when updating delta throws an error") {
    SnapshotState.objectStore.clear()

    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationState
    val deltaStoreSpy = spy(new DeltaStore)
    doThrow(new IllegalArgumentException()).when(deltaStoreSpy).addDelta(any[ClientId], any[Delta])

    val snapshotStateService = new SnapshotStateService {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
      override lazy val deltaStore = deltaStoreSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val notificationStateBefore = notificationStateSpy.get

    val reply = snapshotStateService.updateWith(ClientId("client1"), Seq(publish))

    reply.head should equal(ReportError(BaseError.CouldNotPersist, Some("A problem occurred while persisting the changes: java.lang.IllegalArgumentException")))
    notificationStateSpy.get should equal(notificationStateBefore)
  }

  test("should not update the snapshot, delta and notification state when updating the notification throws an error") {
    SnapshotState.objectStore.clear()

    val repositoryWriterSpy = spy(getRepositoryWriter)
    val notificationStateSpy = getNotificationState
    doThrow(new IllegalArgumentException()).when(repositoryWriterSpy).writeNotification(anyString(), any[Notification])

    val snapshotStateService = new SnapshotStateService {
      override val repositoryWriter = repositoryWriterSpy
      override val notificationState = notificationStateSpy
    }

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val notificationStateBefore = notificationStateSpy.get

    val reply = snapshotStateService.updateWith(ClientId("client1"), Seq(publish))

    reply.tail should equal(Seq(ReportError(BaseError.CouldNotPersist, Some("Could not write XML files to filesystem: null"))))
    verify(repositoryWriterSpy).deleteSnapshot(anyString(), any[ServerState])
    verify(repositoryWriterSpy).deleteDelta(anyString(), any[ServerState])
    verify(repositoryWriterSpy).deleteNotification(anyString())
    notificationStateSpy.get should equal(notificationStateBefore)
  }
  
  def getRepositoryWriter: RepositoryWriter = new MockRepositoryWriter()

  class MockRepositoryWriter extends RepositoryWriter {
    override def writeSnapshot(rootDir: String, serverState: ServerState, snapshot: Snapshot): Unit = { }
    override def writeDelta(rootDir: String, delta: Delta): Unit = { }
    override def writeNotification(rootDir: String, notification: Notification): Path = Paths.get("")
  }

}
