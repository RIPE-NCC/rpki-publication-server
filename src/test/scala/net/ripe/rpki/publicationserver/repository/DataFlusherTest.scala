package net.ripe.rpki.publicationserver.repository

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.fs.Rrdp
import net.ripe.rpki.publicationserver.model._

import java.io.{FileInputStream, InputStreamReader}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.xml.XML

class DataFlusherTest extends PublicationServerBaseTest with Hashing {

  private var rrdpRootDir: Path = _

  private val pgStore = createPgStore

  private val urlPrefix1 = "rsync://host1.com"
  private val urlPrefix2 = "rsync://host2.com"

  private var conf: AppConfig = _

  before {
    pgStore.clear()
  }

  private def newFlusher(writeRrdpFlag: Boolean = true) = {
    rrdpRootDir = Files.createTempDirectory("test_pub_server_rrdp_")
    conf = new AppConfig() {
      override lazy val pgConfig = pgTestConfig
      override lazy val rrdpRepositoryPath = rrdpRootDir.toAbsolutePath.toString
      override lazy val unpublishedFileRetainPeriod = Duration(20, MILLISECONDS)
      override lazy val writeRrdp = writeRrdpFlag
    }
    implicit val healthChecks = new HealthChecks(conf)
    new DataFlusher(conf)
  }

  def waitForRrdpCleanup() = Thread.sleep(200)

  test("Should initialise an empty RRDP repository with no objects") {
    val flusher = newFlusher()
    flusher.initFS()

    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    val (snapshotName, _) = parseNotification(sessionId, serial)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
      </snapshot>"""
    }

    verifyDeltaDoesntExist(sessionId, serial)

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
          </notification>"""
    }
  }

  test("initFS function must be idempotent") {
    def verifyRrdpFiles(sessionId: String, serial: Long) = {
      val (snapshotName, _) = parseNotification(sessionId, serial)

      val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
        s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
      </snapshot>"""
      }

      verifyDeltaDoesntExist(sessionId, serial)

      verifyExpectedNotification {
        s"""<notification version="1" session_id="$sessionId" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/$sessionId/$serial/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
          </notification>"""
      }
    }

    val flusher = newFlusher()
    flusher.initFS()

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

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(INITIAL_SERIAL)

    val (snapshotName, _) = parseNotification(sessionId, serial)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </snapshot>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
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

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri2, tag = None, hash = None, bytes2))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </snapshot>"""
    }

    verifyDeltaDoesntExist(sessionId, serial - 1)

    val deltaBytes2 = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes2).toHex}"/>
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
    flusher.updateFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri3, tag = None, hash = None, bytes3))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
          <publish uri="${uri3}">${base64_3}</publish>
      </snapshot>"""
    }

    verifyDeltaDoesntExist(sessionId, serial-2)

    val deltaBytes2 = verifyExpectedDelta(sessionId, serial-1, deltaNames(serial-1)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial-1}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    val deltaBytes3 = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri3}">${base64_3}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes3).toHex}"/>
            <delta serial="${serial-1}" uri="http://localhost:7788/${sessionId}/${serial-1}/${deltaNames(serial-1)}" hash="${hashOf(deltaBytes2).toHex}"/>
          </notification>"""
    }
  }

  test("Should publish, create a session and serial and generate XML files with updateFS") {
    val flusher = newFlusher()
    flusher.initFS()
    waitForRrdpCleanup()

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
    val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </snapshot>"""
    }

    val deltaBytes = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes).toHex}"/>
          </notification>"""
    }
  }


  test("Should update an empty RRDP repository with no objects") {
    val flusher = newFlusher()
    flusher.updateFS()
    waitForRrdpCleanup()

    verifyNoCurrentSession
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
    val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </snapshot>"""
    }

    val deltaBytes = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri1}">${base64_1}</publish>
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes).toHex}"/>
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

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri2, tag = None, hash = None, bytes2))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri3, tag = None, hash = None, bytes3))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(4)

    val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
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

    val deltaBytes2 = verifyExpectedDelta(sessionId, serial - 1, deltaNames(serial - 1)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial - 1}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri2}">${base64_2}</publish>
      </delta>"""
    }

    val deltaBytes3 = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="${uri3}">${base64_3}</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes3).toHex}"/>
            <delta serial="${serial - 1}" uri="http://localhost:7788/${sessionId}/${serial - 1}/${deltaNames(serial - 1)}" hash="${hashOf(deltaBytes2).toHex}"/>
          </notification>"""
    }
  }

  test("Should combine multiple updates to same URI when generating deltas") {
    val clientId = ClientId("client1")

    val uri0 = new URI(urlPrefix1 + "/large-to-keep-all-deltas.roa")
    val uri1 = new URI(urlPrefix1 + "/path1.roa")

    val (bytes0, base64_0) = TestBinaries.generateObject(10000)
    val (bytes1, base64_1) = TestBinaries.generateObject(100)
    val (bytes2, base64_2) = TestBinaries.generateObject(200)

    val flusher = newFlusher()
    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri0, tag = None, hash = None, bytes0))), clientId)
    flusher.initFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash = hashOf(bytes1)))), clientId)
    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes2))), clientId)

    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(3)

    val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="$uri0">$base64_0</publish>
          <publish uri="$uri1">$base64_2</publish>
      </snapshot>"""
    }

    val deltaBytes2 = verifyExpectedDelta(sessionId, serial - 1, deltaNames(serial - 1)) {
      s"""<delta version="1" session_id="$sessionId" serial="${serial - 1}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="$uri1">$base64_1</publish>
      </delta>"""
    }

    val deltaBytes3 = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
      s"""<delta version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="$uri1" hash="${hashOf(bytes1).toHex}">$base64_2</publish>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes3).toHex}"/>
            <delta serial="${serial - 1}" uri="http://localhost:7788/${sessionId}/${serial - 1}/${deltaNames(serial - 1)}" hash="${hashOf(deltaBytes2).toHex}"/>
          </notification>"""
    }
  }

  test("Should update RRDP when objects are published and withdrawn") {
    val clientId = ClientId("client1")

    val uri0 = new URI(urlPrefix1 + "/large-to-keep-last-delta.roa")
    val uri1 = new URI(urlPrefix1 + "/path1.roa")

    val (bytes0, base64_0) = TestBinaries.generateObject(200)
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(200)

    val flusher = newFlusher()
    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri0, tag = None, hash = None, bytes0))), clientId)
    flusher.initFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = Some(hashOf(bytes1)), bytes2))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash = hashOf(bytes2)))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(4L)

    val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

    verifySnapshotDoesntExist(sessionId, serial - 2)
    verifySnapshotDoesntExist(sessionId, serial - 1)
    verifyDeltaDoesntExist(sessionId, serial - 2)
    verifyDeltaDoesntExist(sessionId, serial - 1)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <publish uri="$uri0">$base64_0</publish>
      </snapshot>""".stripMargin
    }

    val deltaBytes = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
          <withdraw uri="${uri1}" hash="${hashOf(bytes2).toHex}"/>
      </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes).toHex}"/>
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
      val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

      val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
        s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
              <publish uri="${uri1}">${base64_1}</publish>
              <publish uri="${uri2}">${base64_2}</publish>
          </snapshot>"""
      }

      val deltaBytes = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
        s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
              <publish uri="${uri1}">${base64_1}</publish>
              <publish uri="${uri2}">${base64_2}</publish>
          </delta>"""
      }

      verifyExpectedNotification {
        s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes).toHex}"/>
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

  test("Should not update when write-rrdp is disabled") {
    val flusher = newFlusher(writeRrdpFlag = false)
    flusher.initFS()
    waitForRrdpCleanup()

    verifyNoCurrentSession

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix1 + "/path1.roa")

    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(200)

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    verifyNoCurrentSession

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = Some(hashOf(bytes1)), bytes2))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    verifyNoCurrentSession

    rrdpRootDir.resolve("notification.xml").toFile.exists() should be(false)
  }

  test("Should update rrdp when DB is modified by `another instance` in the meantime") {
    val clientId = ClientId("client1")

    val uri0 = new URI(urlPrefix1 + "/large-to-keep-last-delta.roa")
    val uri1 = new URI(urlPrefix1 + "/path1.roa")

    val (bytes0, base64_0) = TestBinaries.generateObject(200)
    val (bytes1, _) = TestBinaries.generateObject(1000)
    val (bytes2, _) = TestBinaries.generateObject(200)

    val flusher = newFlusher()
    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri0, tag = None, hash = None, bytes0))), clientId)
    flusher.initFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = None, bytes1))), clientId)
    flusher.updateFS()
    waitForRrdpCleanup()

    pgStore.applyChanges(QueryMessage(Seq(PublishQ(uri1, tag = None, hash = Some(hashOf(bytes1)), bytes2))), clientId)

    // Freeze version but do not do updateFS, as if some
    // other instance has frozen the version.
    pgStore.inRepeatableReadTx { implicit s =>
      pgStore.freezeVersion
    }

    pgStore.applyChanges(QueryMessage(Seq(WithdrawQ(uri1, tag = None, hash = hashOf(bytes2)))), clientId)

    // this one should catch up and generate two deltas
    flusher.updateFS()
    waitForRrdpCleanup()

    val (sessionId, serial) = verifySessionAndSerial
    serial should be(4L)

    val (snapshotName, deltaNames) = parseNotification(sessionId, serial)

    verifySnapshotDoesntExist(sessionId, serial - 2)
    verifySnapshotDoesntExist(sessionId, serial - 1)
    verifyDeltaDoesntExist(sessionId, serial - 2)
    verifyDeltaDoesntExist(sessionId, serial - 1)

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial, snapshotName) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <publish uri="$uri0">$base64_0</publish>
         </snapshot>"""
    }

    val deltaBytes = verifyExpectedDelta(sessionId, serial, deltaNames(serial)) {
      s"""<delta version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <withdraw uri="${uri1}" hash="${hashOf(bytes2).toHex}"/>
        </delta>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/$snapshotName" hash="${hashOf(snapshotBytes).toHex}"/>
            <delta serial="${serial}" uri="http://localhost:7788/${sessionId}/${serial}/${deltaNames(serial)}" hash="${hashOf(deltaBytes).toHex}"/>
          </notification>"""
    }
  }

  private def verifyExpectedSnapshot(sessionId: String, serial: Long, filename: String)(expected: String): Array[Byte] = {
    val snapshotFile = sessionSerialDir(sessionId, serial).resolve(filename)
    val bytes = Files.readAllBytes(snapshotFile)
    val generatedSnapshot = new String(bytes, StandardCharsets.US_ASCII)
    trim(generatedSnapshot) should be(trim(expected))
    bytes
  }

  private def verifyExpectedDelta(sessionId: String, serial: Long, filename: String)(expected: String) = {
    val deltaFile = sessionSerialDir(sessionId, serial).resolve(filename)
    val bytes = Files.readAllBytes(deltaFile)
    val generatedDelta = new String(bytes, StandardCharsets.US_ASCII)
    trim(generatedDelta) should be(trim(expected))
    bytes
  }

  def sessionSerialDir(sessionId: String, serial: Long) =
    rrdpRootDir.resolve(sessionId).resolve(serial.toString)

  private def verifyNoCurrentSession = {
    pgStore.inRepeatableReadTx { implicit session =>
      pgStore.getCurrentSessionInfo
    } should be(None)
  }

  private def verifySessionAndSerial = {
    val version = pgStore.inRepeatableReadTx { implicit session =>
      pgStore.getCurrentSessionInfo
    }
    version.isDefined should be(true)
    val (versionInfo, _, _) = version.get
    (versionInfo.sessionId, versionInfo.serial)
  }

  private def parseNotification(sessionId: String, serial: Long): (String, SortedMap[Long, String]) = {
    val notification = XML.load(new InputStreamReader(new FileInputStream(rrdpRootDir.resolve("notification.xml").toFile), StandardCharsets.US_ASCII))
    notification \@ "session_id" should be(sessionId)
    notification \@ "serial" should be(serial.toString)

    val snapshotFilename = (notification \ "snapshot" \@ "uri").split('/').last
    val deltaFilenames = (notification \ "delta").map(elem => (elem \@ "serial").toLong -> (elem \@ "uri").split('/').last)

    (snapshotFilename, SortedMap.from(deltaFilenames))
  }

  private def verifyExpectedNotification(expected: String) = {
    val bytes = Files.readAllBytes(rrdpRootDir.resolve("notification.xml"))
    val generatedNotification = new String(bytes, StandardCharsets.US_ASCII)
    trim(generatedNotification) should be(trim(expected))
    bytes
  }

  private def verifySnapshotDoesntExist(sessionId: String, serial: Long) = {
    val path = sessionSerialDir(sessionId, serial)
    if (path.toFile.exists()) {
      val snapshotFound = Files.list(path).
        filter(Rrdp.isSnapshot).
        findFirst().
        isPresent

      snapshotFound should be(false)
    }
  }

  private def verifyDeltaDoesntExist(sessionId: String, serial: Long) = {
    val path = sessionSerialDir(sessionId, serial)
    if (path.toFile.exists()) {
      val deltaFound = Files.list(path).
        filter(Rrdp.isDelta).
        findFirst().
        isPresent

      deltaFound should be(false)
    }
  }

}
