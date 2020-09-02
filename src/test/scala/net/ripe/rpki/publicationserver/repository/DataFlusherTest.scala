package net.ripe.rpki.publicationserver.repository

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import net.ripe.rpki.publicationserver._

class DataFlusherTest  extends PublicationServerBaseTest with Hashing {

  val rsyncRootDir = Files.createTempDirectory( "test_pub_server_rsync_")
  val rrdpRootDfir = Files.createTempDirectory( "test_pub_server_rrdp_")
  val pgStore = PgStore.get(pgTestConfig)

  val urlPrefix = "rsync://host.com"

  private val conf = new AppConfig() {
    override lazy val pgConfig = pgTestConfig
    override lazy val rrdpRepositoryPath = rrdpRootDfir.toAbsolutePath.toString
    override lazy val rsyncRepositoryMapping = Map(
      URI.create(urlPrefix) -> rsyncRootDir
    )
  }

  val flusher = new DataFlusher(conf)

  before {
    pgStore.clear()
  }

  test("Should create an empty RRDP repository with no objects") {
    flusher.initFS()

    val (sessionId, serial) = verifySessionAndSerial

    val snapshotBytes = verifyExpectedSnapshot(sessionId, serial) {
      s"""<snapshot version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
      </snapshot>"""
    }

    verifyExpectedNotification {
      s"""<notification version="1" session_id="${sessionId}" serial="${serial}" xmlns="http://www.ripe.net/rpki/rrdp">
            <snapshot uri="http://localhost:7788/${sessionId}/${serial}/snapshot.xml" hash="${hash(Bytes(snapshotBytes)).hash}"/>
          </notification>"""
    }
  }

  private def verifySessionAndSerial = {
    val version = pgStore.inTx { implicit session =>
      pgStore.getCurrentSessionInfo
    }
    version.isDefined should be(true)
    version.get
  }

  test("Should publish, create a session and serial and generate XML files") {

    val clientId = ClientId("client1")

    val uri1 = new URI(urlPrefix + "/path1")
    val uri2 = new URI(urlPrefix + "/path2")
    val base64_1 = TestBinaries.generateSomeBase64()
    val base64_2 = TestBinaries.generateSomeBase64()
    val bytes1 = Bytes.fromBase64(Base64(base64_1))
    val bytes2 = Bytes.fromBase64(Base64(base64_2))
    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, bytes1),
      PublishQ(uri2, tag=None, hash=None, bytes2),
    ))
    pgStore.applyChanges(changeSet, clientId)

    val state = pgStore.getState

    state should be(Map(
      uri1 -> (bytes1, hash(bytes1), clientId),
      uri2 -> (bytes2, hash(bytes2), clientId)
    ))

    val log = pgStore.getLog
    log should be(Seq(
      ("INS", uri1, None, bytes1),
      ("INS", uri2, None, bytes2)
    ))

    flusher.updateFS

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
            <delta serial="1" uri="http://localhost:7788/${sessionId}/${serial}/delta.xml" hash="${hash(Bytes(deltaBytes)).hash}"/>
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
    val generatedSnapshot = new String(bytes, StandardCharsets.US_ASCII)
    trim(generatedSnapshot) should be(trim(expected))
    bytes
  }

  private def verifyExpectedNotification(expected: String) = {
    val bytes = Files.readAllBytes(rrdpRootDfir.resolve("notification.xml"))
    val generatedNotification = new String(bytes, StandardCharsets.US_ASCII)
    trim(generatedNotification) should be(trim(expected))
    bytes
  }

  test("Should publish, freeze version twice and generate needed deltas") {
    pgStore.inTx { implicit session => pgStore.freezeVersion }

    val clientId = ClientId("client-test")
    val uri1 = new URI(urlPrefix + "/path1")
    val uri2 = new URI(urlPrefix + "/path2")
    val base64_1 = TestBinaries.generateSomeBase64()
    val base64_2 = TestBinaries.generateSomeBase64()
    val changeSet = QueryMessage(Seq(
      PublishQ(uri1, tag=None, hash=None, Bytes.fromBase64(Base64(base64_1))),
      PublishQ(uri2, tag=None, hash=None, Bytes.fromBase64(Base64(base64_2))),
    ))
    pgStore.applyChanges(changeSet, clientId)

    pgStore.inTx { implicit session => pgStore.freezeVersion }

    val uri3 = new URI(urlPrefix + "/path3")
    val changeSet1 = QueryMessage(Seq(
      PublishQ(uri3, tag=None, hash=None, Bytes.fromBase64(Base64("BBBBAABABABABABABABBBABBABBBABBABB==")))
    ))
    pgStore.applyChanges(changeSet1, clientId)

    flusher.updateFS



  }



}
