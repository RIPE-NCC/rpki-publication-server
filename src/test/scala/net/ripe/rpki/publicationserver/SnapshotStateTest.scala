package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.Paths
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.fs._
import net.ripe.rpki.publicationserver.store.{DeltaStore, Migrations, ObjectStore, ServerStateStore}
import org.mockito.Matchers._
import org.mockito.Mockito._

import scala.concurrent.duration.Duration
import scala.util.Try

class SnapshotStateTest extends PublicationServerBaseTest with Config with Hashing {

  private var serial: Long = _

  private var sessionId: UUID = _

  private val deltaStore = DeltaStore.get

  private val serverStateStore = new ServerStateStore

  private val objectStore = new ObjectStore

  implicit private val system = ActorSystem("MyActorSystem", ConfigFactory.load())
  
  private val fsWriterRef = TestActorRef[FSWriterActor]

  before {
    serial = 1L
    objectStore.clear()
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
    val snapshotState = new SnapshotStateService { }
    snapshotState.init(fsWriterRef)

    snapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))

    snapshotState.deltaStore.getDeltas should be(Seq(
      Delta(
        sessionId,
        2L,
        Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa=")))
      )
    ))
  }

  test("should update an object with publish and republish") {
    val snapshotState = new SnapshotStateService {
      override lazy val deltaStore = new DeltaStore { }
    }
    snapshotState.init(fsWriterRef)

    snapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))

    val replies = snapshotState.updateWith(
      ClientId("bla"),
      Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"),
        tag = None,
        hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
        base64 = Base64("cccc="))))

    snapshotState.deltaStore.getDeltas.toList should be(Seq(
      Delta(
        sessionId,
        2L,
        List(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa=")))
      ),
      Delta(
        sessionId,
        3L,
        List(PublishQ(uri = new URI("rsync://host/zzz.cer"),
          tag = None,
          hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
          base64 = Base64("cccc=")))
      )
    ))
  }

  test("should fail to update an object which is not in the snapshot") {
    val snapshotState = new SnapshotStateService { }
    snapshotState.init(fsWriterRef)

    val replies = snapshotState.updateWith(
      ClientId("bla"),
      Seq(PublishQ(
        uri = new URI("rsync://host/not-existing.cer"),
        tag = None,
        hash = Some("BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7"),
        base64 = Base64("cccc="))))

    replies.head should be(ReportError(BaseError.NoObjectToUpdate, Some("No object [rsync://host/not-existing.cer] has been found.")))
  }

  test("should fail to update an object without hash provided") {
    val snapshotState = new SnapshotStateService {
      override lazy val deltaStore = new DeltaStore { }
    }
    snapshotState.init(fsWriterRef)

    snapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))

    val replies = snapshotState.updateWith(
      ClientId("bla"),Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("cccc="))))

    replies.head should be(ReportError(BaseError.HashForInsert, Some("Tried to insert existing object [rsync://host/zzz.cer].")))
  }

  test("should fail to update an object if hashes do not match") {
    val snapshotState = new SnapshotStateService { }
    snapshotState.init(fsWriterRef)

    snapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))

    val replies = snapshotState.updateWith(
      ClientId("bla"),
      Seq(PublishQ(
      uri = new URI("rsync://host/zzz.cer"),
      tag = None,
      hash = Some("WRONGHASH"),
      base64 = Base64("cccc="))))

    replies.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot republish the object [rsync://host/zzz.cer], hash doesn't match")))
  }

  test("should fail to withdraw an object if there's no such object") {
    val snapshotState = new SnapshotStateService { }
    snapshotState.init(fsWriterRef)

    val replies = snapshotState.updateWith(
      ClientId("bla"),Seq(WithdrawQ(uri = new URI("rsync://host/not-existing-uri.cer"), tag = None, hash = "whatever")))

    replies.head should be(ReportError(BaseError.NoObjectForWithdraw, Some("No object [rsync://host/not-existing-uri.cer] found.")))
  }

  test("should fail to withdraw an object if hashes do not match") {
    val snapshotState = new SnapshotStateService { }
    snapshotState.init(fsWriterRef)

    snapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = None, base64 = Base64("aaaa="))))

    val replies = snapshotState.updateWith(
      ClientId("bla"),Seq(WithdrawQ(uri = new URI("rsync://host/zzz.cer"), tag = None, hash = "WRONGHASH")))

    replies.head should be(ReportError(BaseError.NonMatchingHash, Some("Cannot withdraw the object [rsync://host/zzz.cer], hash doesn't match.")))
  }

  test("should create 2 entries in delta map after 2 updates") {
    val snapshotState = new SnapshotStateService { }
    snapshotState.init(fsWriterRef)

    snapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/cert1.cer"), tag = None, hash = None, base64 = Base64("cccc="))))

    snapshotState.updateWith(ClientId("bla"), Seq(PublishQ(uri = new URI("rsync://host/cert2.cer"), tag = None, hash = None, base64 = Base64("bbbb="))))

    snapshotState.deltaStore.getDeltas.size should be(2)
    snapshotState.deltaStore.getDeltas.head.pdus should be(Seq(PublishQ(uri = new URI("rsync://host/cert1.cer"), tag = None, hash = None, base64 = Base64("cccc="))))
    snapshotState.deltaStore.getDeltas.tail.head.pdus should be(Seq(PublishQ(uri = new URI("rsync://host/cert2.cer"), tag = None, hash = None, base64 = Base64("bbbb="))))
  }

  test("should write the snapshot and the deltas to the filesystem when a message is successfully processed") {
    val snapshotState = new SnapshotStateService { }
    snapshotState.objectStore.clear()

    val fsWriterSpy = TestProbe()
    val deltaCleanSpy = TestProbe()
    snapshotState.init(fsWriterSpy.ref)
    fsWriterSpy.expectMsgType[WriteCommand]

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))

    snapshotState.updateWith(ClientId("client1"), Seq(publish))
    fsWriterSpy.expectMsgType[WriteCommand]
  }

  test("should clean old deltas when updating filesystem") {
    val snapshotState = new SnapshotStateService { }

    val fsWriterSpy = TestProbe()
    val deltaCleanSpy = TestProbe()
    snapshotState.init(fsWriterSpy.ref)
    fsWriterSpy.expectMsgType[WriteCommand]

    val publish = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("aaaa="))

    snapshotState.updateWith(ClientId("client1"), Seq(publish))
    fsWriterSpy.expectMsgType[WriteCommand]
  }

  test("should not write a snapshot to the filesystem when a message contained an error") {
    val snapshotState = new SnapshotStateService { }
    snapshotState.objectStore.clear()

    val fsWriterSpy = TestProbe()
    val deltaCleanSpy = TestProbe()
    snapshotState.init(fsWriterSpy.ref)
    fsWriterSpy.expectMsgType[WriteCommand]

    val withdraw = WithdrawQ(new URI("rsync://host/zzz.cer"), None, "BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7")

    // The withdraw will fail because the SnapshotState is still empty
    fsWriterSpy.expectNoMsg()
    deltaCleanSpy.expectNoMsg()
  }

  test("should not write a snapshot to the filesystem when updating delta throws an error") {
    val snapshotState = new SnapshotStateService { }
    snapshotState.objectStore.clear()

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
    val deltaStoreSpy = spy(new DeltaStore)
    val fsWriterSpy = TestProbe()
    val snapshotStateService = new SnapshotStateService {
      override lazy val deltaStore = deltaStoreSpy
      override def snapshotRetainPeriod = Duration.Zero
    }
    snapshotStateService.init(fsWriterSpy.ref)
    snapshotStateService.objectStore.clear()
    fsWriterSpy.expectMsg(WriteCommand(ServerState(sessionId, 1)))
    fsWriterSpy.expectNoMsg()

    val publish1 = PublishQ(new URI("rsync://host/xxx.cer"), None, None, Base64("aaaa="))
    val withdraw1 = WithdrawQ(new URI("rsync://host/xxx.cer"), None, "BBA9DB5E8BE9B6876BB90D0018115E23FC741BA6BF2325E7FCF88EFED750C4C7")

    val publish2 = PublishQ(new URI("rsync://host/zzz.cer"), None, None, Base64("bbbbbb="))
    val withdraw2 = WithdrawQ(new URI("rsync://host/zzz.cer"), None, stringify(hash(Base64("bbbbbb="))))

    snapshotStateService.updateWith(ClientId("client1"), Seq(publish1))
    snapshotStateService.updateWith(ClientId("client1"), Seq(withdraw1))
    snapshotStateService.updateWith(ClientId("client2"), Seq(publish2))
    snapshotStateService.updateWith(ClientId("client2"), Seq(withdraw2))

  }


  def getRepositoryWriter: RepositoryWriter = new MockRepositoryWriter()

  class MockRepositoryWriter extends RepositoryWriter {
    override def writeSnapshot(rootDir: String, serverState: ServerState, snapshot: Snapshot) = Paths.get("")
    override def writeDelta(rootDir: String, delta: Delta) = Try(Paths.get(""))
    override def writeNotification(rootDir: String, notification: Notification) = None
  }

}