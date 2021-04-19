package net.ripe.rpki.publicationserver.repository

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, Path}

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model._

import scala.concurrent.duration._

class DataFlusherTest extends PublicationServerBaseTest with Hashing {

  private var rsyncRootDir1: Path = _
  private var rsyncRootDir2: Path = _
  private var rrdpRootDfir: Path = _

  private val pgStore = createPgStore

  private val urlPrefix1 = "rsync://host1.com"
  private val urlPrefix2 = "rsync://host2.com"

  private var conf: AppConfig = _

  before {
    pgStore.clear()
  }

  private def newFlusher(writeRsyncFlag: Boolean = true, writeRrdpFlag: Boolean = true) = {
    rsyncRootDir1 = Files.createTempDirectory("test_pub_server_rsync_")
    rsyncRootDir2 = Files.createTempDirectory("test_pub_server_rsync_")
    rrdpRootDfir = Files.createTempDirectory("test_pub_server_rrdp_")
    conf = new AppConfig() {
      override lazy val pgConfig = pgTestConfig
      override lazy val rrdpRepositoryPath = rrdpRootDfir.toAbsolutePath.toString
      override lazy val unpublishedFileRetainPeriod = Duration(20, MILLISECONDS)
      override lazy val writeRsync = writeRsyncFlag
      override lazy val writeRrdp = writeRrdpFlag
      override lazy val rsyncRepositoryMapping = Map(
        URI.create(urlPrefix1) -> rsyncRootDir1,
        URI.create(urlPrefix2) -> rsyncRootDir2
      )
    }
    new DataFlusher(conf)
  }

  def waitForRrdpCleanup() = Thread.sleep(200)

  test("Should initialise an empty RRDP repository with no objects") {
    val flusher = newFlusher()
    flusher.initFS()

    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
      </snapshot>"""
    }

    try {
      val deltaBytes = Files.readAllBytes(rrdpRootDfir.resolve(sessionId).resolve(serial.toString).resolve("delta.xml"))
      if (deltaBytes != null) fail()
    } catch {
      case e: java.nio.file.NoSuchFileException => ()
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
          </notification>"""
    }
  }

  test("initFS function must be idempotent") {
    val flusher = newFlusher()
    flusher.initFS()

    def verifyRrdpFiles(sessionId: String, serial: Long) = {
      val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
        s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
      </snapshot>"""
      }

      try {
        val deltaBytes = Files.readAllBytes(rrdpRootDfir.resolve(sessionId).resolve(serial.toString).resolve("delta.xml"))
        if (deltaBytes != null) fail()
      } catch {
        case e: NoSuchFileException => ()
      }

      verifyExpectedNotification {
        s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
          </notification>"""
      }
    }

    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    verifyRrdpFiles(sessionId, serial)

    flusher.initFS()
    waitForRrdpCleanup()

    val (sessionId1, serial1) = verifySessionAndSerial
    sessionId1 should be(sessionId)
    serial1 should be(serial)
    verifyRrdpFiles(sessionId1, serial1)
  }

  test("Should initialise an RRDP repository with a couple of objects published at once") {
    val flusher = newFlusher()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.cer")
    val uri2 = new URI(urlPrefix2 + "/directory/path2.cer")

    val (bytes1, base64_1) = TestBinaries.generateObject()
    val (bytes2, base64_2) = TestBinaries.generateObject()
    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, bytes1),
      PublishQ(uri2, tag=None, hash=None, bytes2),
    ))
    pgStore.applyChanges(changeSet, clientId)

    flusher.initFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("path1.cer"))) should be(bytes1)
    Bytes(Files.readAllBytes(rsyncRootDir2.resolve("online")
        .resolve("directory").resolve("path2.cer"))) should be(bytes2)

    val (sessionId, serial) = verifySessionAndSerial

    serial should be(INITIAL_SERIAL)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </snapshot>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
          </notification>"""
    }
  }

  test("Should initialise an RRDP repository with a few objects published twice, " +
    "first delta is not published because of the size") {
    val flusher = newFlusher()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.cer")
    val uri2 = new URI(urlPrefix2 + "/directory/path2.cer")
    val (bytes1, base64_1) = TestBinaries.generateObject()
    val (bytes2, base64_2) = TestBinaries.generateObject()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.initFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("path1.cer"))) should be(bytes1)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri2, tag = None, hash = None, bytes2))), clientId)
    flusher.initFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir2.resolve("online").resolve("directory").resolve("path2.cer"))) should be(bytes2)

    val (sessionId, serial) = verifySessionAndSerial

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </snapshot>"""
    }

    verifyDeltaDoesntExist(sessionId, serial - 1)

    val deltaBytes2 = verifyExpectedDelta(sessionId, serial) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/delta.xml" hash="${hash(Bytes(deltaBytes2)).hash}"/>
          </notification>"""
    }
  }


  test("Should initialise an RRDP repository with a few objects published twice, two deltas are published") {
    val flusher = newFlusher()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.roa")
    val uri2 = new URI(urlPrefix1 + "/pbla/path2.cer")
    val uri3 = new URI(urlPrefix2 + "/x/y/z/path3.mft")
    // generate some bigger objects so that the size of the snapshot would be big
    val (bytes1, base64_1) = TestBinaries.generateObject(100)
    val (bytes2, base64_2) = TestBinaries.generateObject(10)
    val (bytes3, base64_3) = TestBinaries.generateObject(10)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.initFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri2, tag = None, hash = None, bytes2))), clientId)
    flusher.initFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri3, tag = None, hash = None, bytes3))), clientId)
    flusher.initFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("path1.roa"))) should be(bytes1)
    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("pbla").resolve("path2.cer"))) should be(bytes2)
    Bytes(Files.readAllBytes(rsyncRootDir2.resolve("online")
      .resolve("x")
      .resolve("y")
      .resolve("z")
      .resolve("path3.mft"))) should be(bytes3)

    val (sessionId, serial) = verifySessionAndSerial

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
          <publish uri="${uri3}">${base64_3}</publish>
      </snapshot>"""
    }

    verifyDeltaDoesntExist(sessionId, serial-2)

    val deltaBytes2 = verifyExpectedDelta(sessionId, serial-1) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial-1}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    val deltaBytes3 = verifyExpectedDelta(sessionId, serial) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri3}">${base64_3}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/delta.xml" hash="${hash(Bytes(deltaBytes3)).hash}"/>
            <delta serial="${serial-1}" uri="http://localhost:7788/${sessionId}/${serial-1}/delta.xml" hash="${hash(Bytes(deltaBytes2)).hash}"/>
          </notification>"""
    }
  }

  test("Should publish, create a session and serial and generate XML files with updateFS") {
    val flusher = newFlusher()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val uri2 = new URI(urlPrefix2 + "/path2")
    val (bytes1, base64_1) = TestBinaries.generateObject(1000)
    val (bytes2, base64_2) = TestBinaries.generateObject(500)
    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, bytes1),
      PublishQ(uri2, tag=None, hash=None, bytes2),
    ))
    pgStore.applyChanges(changeSet, clientId)

    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </snapshot>"""
    }

    val deltaBytes = verifyExpectedDelta(sessionId, serial) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/delta.xml" hash="${hash(Bytes(deltaBytes)).hash}"/>
          </notification>"""
    }
  }


  test("Should update an empty RRDP repository with no objects") {
    val flusher = newFlusher()
    flusher.updateFS()
    waitForRrdpCleanup()

    pgStore.inRepeatableReadTx { implicit session =>
      pgStore.getCurrentSessionInfo
    } should be(None)
  }

  test("Should update an RRDP repository with a couple of objects published at once") {
    val flusher = newFlusher()
    flusher.initFS()
    waitForRrdpCleanup()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val uri2 = new URI(urlPrefix2 + "/path2")

    val (bytes1, base64_1) = TestBinaries.generateObject()
    val (bytes2, base64_2) = TestBinaries.generateObject()
    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, bytes1),
      PublishQ(uri2, tag=None, hash=None, bytes2),
    ))
    pgStore.applyChanges(changeSet, clientId)

    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </snapshot>"""
    }

    val deltaBytes = verifyExpectedDelta(sessionId, serial) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/delta.xml" hash="${hash(Bytes(deltaBytes)).hash}"/>
          </notification>"""
    }
  }

  test("Should update an RRDP repository after 3 publishes (only two deltas because of the size)") {
    val flusher = newFlusher()
    flusher.initFS()
    waitForRrdpCleanup()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.roa")
    val uri2 = new URI(urlPrefix1 + "/q/path2.cer")
    val uri3 = new URI(urlPrefix2 + "/path3.crl")

    val (bytes1, base64_1) = TestBinaries.generateObject(1000)
    val (bytes2, base64_2) = TestBinaries.generateObject(200)
    val (bytes3, base64_3) = TestBinaries.generateObject(100)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("path1.roa"))) should be(bytes1)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri2, tag = None, hash = None, bytes2))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("q").resolve("path2.cer"))) should be(bytes2)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri3, tag = None, hash = None, bytes3))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir2.resolve("online").resolve("path3.crl"))) should be(bytes3)

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(4)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
          <publish uri="${uri3}">${base64_3}</publish>
      </snapshot>"""
    }

    verifySnapshotDoesntExist(sessionId, serial - 3)
    verifySnapshotDoesntExist(sessionId, serial - 2)
    verifyDeltaDoesntExist(sessionId, serial - 3)
    verifyDeltaDoesntExist(sessionId, serial - 2)

    val deltaBytes2 = verifyExpectedDelta(sessionId, serial - 1) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial - 1}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    val deltaBytes3 = verifyExpectedDelta(sessionId, serial) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri3}">${base64_3}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/delta.xml" hash="${hash(Bytes(deltaBytes3)).hash}"/>
            <delta serial="${serial - 1}" uri="http://localhost:7788/${sessionId}/${serial - 1}/delta.xml" hash="${hash(Bytes(deltaBytes2)).hash}"/>
          </notification>"""
    }
  }


  test("Should update RRDP and rsync when objects are published and withdrawn") {
    val flusher = newFlusher()
    flusher.initFS()
    waitForRrdpCleanup()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.roa")

    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(200)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("path1.roa"))) should be(bytes1)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = Some(hash(bytes1).hash), bytes2))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("path1.roa"))) should be(bytes2)

    pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash = hash(bytes2).hash))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    rsyncRootDir1.resolve("online").resolve("path1.roa").toFile.exists() should be(false)

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(4L)

    verifySnapshotDoesntExist(sessionId, serial - 2)
    verifySnapshotDoesntExist(sessionId, serial - 1)
    verifyDeltaDoesntExist(sessionId, serial - 2)
    verifyDeltaDoesntExist(sessionId, serial - 1)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp"></snapshot>"""
    }

    verifyExpectedDelta(sessionId, serial) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <withdraw uri="${uri1}" hash="${hash(bytes2).hash}"/>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
          </notification>"""
    }
  }

  test("updateFS function must be idempotent") {
    val flusher = newFlusher()

    flusher.initFS()
    waitForRrdpCleanup()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1")
    val uri2 = new URI(urlPrefix2 + "/path2")

    val (bytes1, base64_1) = TestBinaries.generateObject()
    val (bytes2, base64_2) = TestBinaries.generateObject()

    def verifyRrpdFiles(sessionId : String, serial: Long) = {
      val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
        s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
              <publish uri="${uri1}">${base64_1}</publish>
              <publish uri="${uri2}">${base64_2}</publish>
          </snapshot>"""
      }

      val deltaBytes = verifyExpectedDelta(sessionId, serial) {
        s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
              <publish uri="${uri1}">${base64_1}</publish>
              <publish uri="${uri2}">${base64_2}</publish>
          </delta>"""
      }

      verifyExpectedNotification {
        s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/delta.xml" hash="${hash(Bytes(deltaBytes)).hash}"/>
          </notification>"""
      }
    }

    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, bytes1),
      PublishQ(uri2, tag=None, hash=None, bytes2),
    ))
    pgStore.applyChanges(changeSet, clientId)

    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    verifyRrpdFiles(sessionId, serial)

    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId1, serial1) = verifySessionAndSerial
    sessionId1 should be(sessionId)
    serial1 should be(serial)
    verifyRrpdFiles(sessionId1, serial1)
  }


  test("Should update RRDP only when set to do write-rrdp") {
    val flusher = newFlusher(writeRsyncFlag = false)
    flusher.initFS()
    waitForRrdpCleanup()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.roa")

    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(200)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    rsyncRootDir1.resolve("online").resolve("path1.roa").toFile.exists() should be(false)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = Some(hash(bytes1).hash), bytes2))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    rsyncRootDir1.resolve("online").resolve("path1.roa").toFile.exists() should be(false)

    pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash = hash(bytes2).hash))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    rsyncRootDir1.resolve("online").resolve("path1.roa").toFile.exists() should be(false)

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(4L)

    verifySnapshotDoesntExist(sessionId, serial - 2)
    verifySnapshotDoesntExist(sessionId, serial - 1)
    verifyDeltaDoesntExist(sessionId, serial - 2)
    verifyDeltaDoesntExist(sessionId, serial - 1)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp"></snapshot>"""
    }

    verifyExpectedDelta(sessionId, serial) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <withdraw uri="${uri1}" hash="${hash(bytes2).hash}"/>
        </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
          </notification>"""
    }
  }

  test("Should update rsync only when set to do write-rsync") {
    val flusher = newFlusher(writeRrdpFlag = false)
    flusher.initFS()
    waitForRrdpCleanup()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.roa")

    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(200)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("path1.roa"))) should be(bytes1)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = Some(hash(bytes1).hash), bytes2))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    Bytes(Files.readAllBytes(rsyncRootDir1.resolve("online").resolve("path1.roa"))) should be(bytes2)

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(3L)

    verifySnapshotDoesntExist(sessionId, serial - 1)
    verifySnapshotDoesntExist(sessionId, serial)
    verifyDeltaDoesntExist(sessionId, serial - 1)
    verifyDeltaDoesntExist(sessionId, serial)

    rrdpRootDfir.resolve("notification.xml").toFile.exists() should be(false)
  }


  test("Should update rrdp and rsync when DB is modified by `another instance` in the meantime") {
    val flusher = newFlusher()
    flusher.initFS()
    waitForRrdpCleanup()

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.roa")

    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(200)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = Some(hash(bytes1).hash), bytes2))), clientId)

    // Freeze version but do not do updateFS, as if some
    // other instance has frozen the version.
    pgStore.inRepeatableReadTx { implicit s =>
      pgStore.freezeVersion
    }

    pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash = hash(bytes2).hash))), clientId)

    // this one should catch up and generate two deltas and modify two objects to the rsync repo
    flusher.updateFS()
    waitForRrdpCleanup()

    rsyncRootDir1.resolve("online").resolve("path1.roa").toFile.exists() should be(false)

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(4L)

    verifySnapshotDoesntExist(sessionId, serial - 2)
    verifySnapshotDoesntExist(sessionId, serial - 1)
    verifyDeltaDoesntExist(sessionId, serial - 2)
    verifyDeltaDoesntExist(sessionId, serial - 1)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp"></snapshot>"""
    }

    verifyExpectedDelta(sessionId, serial) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <withdraw uri="${uri1}" hash="${hash(bytes2).hash}"/>
        </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
          </notification>"""
    }
  }


  private def verifyExpectedSnapshot(sessionId: String, serial: Long)(expected: String) = {
    val bytes = Files.readAllBytes(rrdpRootDfir.resolve(sessionId).resolve(serial.toString).resolve("snapshot.xml"))
    val generatedSnapshot = new String(bytes, StandardCharsets.US_ASCII)
    trim(generatedSnapshot) should be(trim(expected))
    bytes
  }

  private def verifyExpectedDelta(sessionId: String, serial: Long)(expected: String) = {
    val bytes = Files.readAllBytes(rrdpRootDfir.resolve(sessionId).resolve(serial.toString).resolve("delta.xml"))
    val generatedDelta = new String(bytes, StandardCharsets.US_ASCII)
    trim(generatedDelta) should be(trim(expected))
    bytes
  }

  private def verifySessionAndSerial = {
    val version = pgStore.inRepeatableReadTx { implicit session =>
      pgStore.getCurrentSessionInfo
    }
    version.isDefined should be(true)
    version.get
  }

  private def verifyExpectedNotification(expected: String) = {
    val bytes = Files.readAllBytes(rrdpRootDfir.resolve("notification.xml"))
    val generatedNotification = new String(bytes, StandardCharsets.US_ASCII)
    trim(generatedNotification) should be(trim(expected))
    bytes
  }

  private def verifySnapshotDoesntExist(sessionId: String, serial: Long) =
    rrdpRootDfir.resolve(sessionId).resolve(serial.toString).resolve("snapshot.xml").toFile.exists() should be(false)

  private def verifyDeltaDoesntExist(sessionId: String, serial: Long) =
    rrdpRootDfir.resolve(sessionId).resolve(serial.toString).resolve("delta.xml").toFile.exists() should be(false)


}
