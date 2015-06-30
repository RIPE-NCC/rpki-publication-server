package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Files, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit

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

class RepositoryStateTest extends PublicationServerBaseTest with Hashing {

  private var serial: Long = _

  private var sessionId: UUID = _

  private val deltaStore = DeltaStore.get

  private val serverStateStore = new ServerStateStore

  private val objectStore = new ObjectStore

  implicit private val system = ActorSystem("MyActorSystem", ConfigFactory.load())
  
  private val fsWriterRef = TestActorRef[FSWriterActor]

  val rootDir = Files.createTempDirectory(Paths.get("/tmp"),"test")


    val conf_ = new AppConfig {
      override lazy val snapshotRetainPeriod = Duration.Zero
      override lazy val locationRepositoryPath = rootDir.toString
    }


  objectStore.clear()
  SnapshotState.deltaStore.clear()

  before {
    serial = 1L
    deltaStore.clear()
    serverStateStore.clear()
    Migrations.initServerState()
    sessionId = serverStateStore.get.sessionId
  }


  test("should write the snapshot and the deltas to the filesystem when a message is successfully processed") {
    SnapshotState.objectStore.clear()

    val fsWriterSpy = TestProbe()

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
    val snapshotStateService = new SnapshotStateService with Config {
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
    val snapshotStateService = new SnapshotStateService {
      override lazy val deltaStore = deltaStoreSpy
      override def snapshotRetainPeriod = Duration(-1L, TimeUnit.SECONDS)
    }
    snapshotStateService.init(fsWriterSpy.ref)
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
