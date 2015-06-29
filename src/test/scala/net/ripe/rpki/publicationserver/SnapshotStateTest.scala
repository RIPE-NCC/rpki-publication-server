package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.fs._
import net.ripe.rpki.publicationserver.store.{DeltaStore, Migrations, ObjectStore, ServerStateStore}
import org.mockito.Matchers._
import org.mockito.Mockito._

class SnapshotStateTest extends PublicationServerBaseTest with Config with Hashing {

  private var serial: Long = _

  private var sessionId: UUID = _

  private val deltaStore = DeltaStore.get

  private val serverStateStore = new ServerStateStore

  private val objectStore = new ObjectStore

  implicit private val system = ActorSystem("MyActorSystem", ConfigFactory.load())
  
  private val fsWriterRef = TestActorRef[FSWriterActor]

  objectStore.clear()
  SnapshotState.deltaStore.clear()

  before {
    serial = 1L
    deltaStore.clear()
    serverStateStore.clear()
    Migrations.initServerState()
    sessionId = serverStateStore.get.sessionId
  }

  test("should write the snapshot and delta's from the db to the filesystem on init") {
    val mockDeltaStore = spy(new DeltaStore)

    val snapshotState = new SnapshotStateService {
      override lazy val deltaStore = mockDeltaStore
    }
    when(mockDeltaStore.getDeltas).thenReturn(Seq(Delta(sessionId, 1L, Seq.empty)))

    snapshotState.init(fsWriterRef)

    verify(mockDeltaStore).initCache(any[UUID])
  }

  test("should add an object with publish") {
    SnapshotState.init(fsWriterRef)

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
    SnapshotState.init(fsWriterRef)

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
    SnapshotState.init(fsWriterRef)

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
    SnapshotState.init(fsWriterRef)

    val replies = SnapshotState.updateWith(
      ClientId("bla"),Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("cccc="))))

    replies.head should be(ReportError(BaseError.HashForInsert, Some("Tried to insert existing object [rsync://host/zzz.cer].")))
  }

  test("should fail to update an object if hashes do not match") {
    SnapshotState.init(fsWriterRef)

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
    SnapshotState.init(fsWriterRef)

    val replies = SnapshotState.updateWith(
      ClientId("bla"),Seq(WithdrawQ(uri = new URI("rsync://host/not-existing-uri.cer"), tag = None, hash = "whatever")))

    replies.head should be(ReportError(BaseError.NoObjectForWithdraw, Some("No object [rsync://host/not-existing-uri.cer] found.")))
  }

  test("should fail to withdraw an object if hashes do not match") {
    SnapshotState.init(fsWriterRef)

    val replies = SnapshotState.updateWith(
      ClientId("bla"),Seq(WithdrawQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = "WRONGHASH")))

    replies.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot withdraw the object [rsync://host/zzz.cer], hash doesn't match.")))
  }

  test("should create 2 entries in delta map after 2 updates") {
    SnapshotState.init(fsWriterRef)

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

  test("should write the snapshot and the deltas to the filesystem when a message is successfully processed") {
    SnapshotState.objectStore.clear()

    val fsWriterSpy = TestProbe()
    val deltaCleanSpy = TestProbe()
    SnapshotState.init(fsWriterSpy.ref)
    fsWriterSpy.expectMsgType[WriteCommand]

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))

    SnapshotState.updateWith(ClientId("client1"), Seq(publish))
    fsWriterSpy.expectMsgType[WriteCommand]
  }

  test("should clean old deltas when updating filesystem") {
    SnapshotState.objectStore.clear()
    SnapshotState.deltaStore

    val fsWriterSpy = TestProbe()
    val deltaCleanSpy = TestProbe()
    SnapshotState.init(fsWriterSpy.ref)
    fsWriterSpy.expectMsgType[WriteCommand]

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))

    SnapshotState.updateWith(ClientId("client1"), Seq(publish))
    fsWriterSpy.expectMsgType[WriteCommand]
  }

  test("should not write a snapshot to the filesystem when a message contained an error") {
    SnapshotState.objectStore.clear()

    val fsWriterSpy = TestProbe()
    val deltaCleanSpy = TestProbe()
    SnapshotState.init(fsWriterSpy.ref)
    fsWriterSpy.expectMsgType[WriteCommand]

    val withdraw = WithdrawQ(new URI("rsync://host/zzz.cer"), None, "BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7")

    // The withdraw will fail because the SnapshotState is still empty
    fsWriterSpy.expectNoMsg()
    deltaCleanSpy.expectNoMsg()
  }

  test("should not write a snapshot to the filesystem when updating delta throws an error") {
    SnapshotState.objectStore.clear()

    val deltaStoreSpy = spy(new DeltaStore)
    doThrow(new IllegalArgumentException()).when(deltaStoreSpy).addDeltaAction(any[ClientId], any[Delta])

    val fsWriterSpy = TestProbe()
    val deltaCleanSpy = TestProbe()
    val snapshotStateService = new SnapshotStateService {
      override lazy val deltaStore = deltaStoreSpy
    }
    snapshotStateService.init(fsWriterSpy.ref)
    fsWriterSpy.expectMsgType[WriteCommand]

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))
    val reply = snapshotStateService.updateWith(ClientId("client1"), Seq(publish))

    reply.head should equal(ReportError(BaseError.CouldNotPersist, Some("A problem occurred while persisting the changes: java.lang.IllegalArgumentException")))
    fsWriterSpy.expectNoMsg()
    deltaCleanSpy.expectNoMsg()
  }

  test("should delete older deltas when they are too big") {
    SnapshotState.objectStore.clear()

    val deltaStoreSpy = spy(new DeltaStore)
    val fsWriterSpy = TestProbe()
    val deltaCleanSpy = TestProbe()
    val snapshotStateService = new SnapshotStateService {
      override lazy val deltaStore = deltaStoreSpy
      override def snapshotRetainPeriod = -1L
    }
    snapshotStateService.init(fsWriterSpy.ref)
    fsWriterSpy.expectMsgType[WriteCommand]

    val publish1 = PublishQ(new URI("rsync://host/xxx.cer"), None, None, Base64("aaaa="))
    val withdraw1 = WithdrawQ(new URI("rsync://host/xxxss.cer"), None, "BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7")

    val publish2 = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("bbbbbb="))
    val withdraw2 = WithdrawQ(new URI("rsync://host/zzz.cer"), None, stringify(hash(Base64("bbbbbb="))))

    snapshotStateService.updateWith(ClientId("client1"), Seq(publish1))
    snapshotStateService.updateWith(ClientId("client1"), Seq(withdraw1))
    snapshotStateService.updateWith(ClientId("client2"), Seq(publish2))
    snapshotStateService.updateWith(ClientId("client2"), Seq(withdraw2))

    deltaCleanSpy.expectNoMsg()
  }


  def getRepositoryWriter: RepositoryWriter = new MockRepositoryWriter()

  class MockRepositoryWriter extends RepositoryWriter {
    override def writeSnapshot(rootDir: String, serverState: ServerState, snapshot: Snapshot) = Paths.get("")
    override def writeDelta(rootDir: String, delta: Delta) = Paths.get("")
    override def writeNotification(rootDir: String, notification: Notification) = None
  }

}
