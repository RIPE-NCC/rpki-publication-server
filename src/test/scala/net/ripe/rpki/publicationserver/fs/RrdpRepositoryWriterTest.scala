package net.ripe.rpki.publicationserver.fs

import net.ripe.rpki.publicationserver.PublicationServerBaseTest

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.util.Random

class RrdpRepositoryWriterTest extends PublicationServerBaseTest {

  lazy val subject = new RrdpRepositoryWriter

  lazy val rootDir = Files.createTempDirectory("test_rrdp_writer")
  deleteOnExit(rootDir)

  private val objectStore = createPgStore

  before{
    objectStore.clear()
  }

  test("should delete old snapshots") {
    val timestamp = System.currentTimeMillis()
    val repoFiles = setupTestRepo(timestamp)
    val deleteTimestamp = timestamp - 5000
    val (toDelete, toKeep) = repoFiles.partition(f => Rrdp.isSnapshot(f) && mtimeIsBefore(f, deleteTimestamp))
    assume(toDelete.nonEmpty)
    assume(toKeep.nonEmpty)

    subject.deleteSnapshotsOlderThan(rootDir.toString, FileTime.fromMillis(deleteTimestamp), 0)

    toDelete.filter(Files.exists(_)) shouldBe empty
    toKeep.filter(Files.notExists(_)) shouldBe empty
  }

  test("should delete deltas") {
    val (sessionId, serials) = setupDeltas()
    subject.deleteDeltas(rootDir.toString, sessionId, serials.toSet)

    serials.filter(serial =>
      Files.exists(Paths.get(rootDir.toString, sessionId.toString, serial.toString, "delta-1.xml"))
    ) shouldBe empty
  }

  def setupTestRepo(timestamp: Long): Seq[Path] = {
    (for {
      sessionDir <- (1 to 10).map(_ => Files.createTempDirectory(rootDir, "session"))
      serialDir <- (1 to 10).map(_ => Files.createTempDirectory(sessionDir, "serial"))
    } yield {
      val fileTime: FileTime = FileTime.fromMillis(timestamp - Random.nextInt(10 * 1000))
      val delta = Files.createFile(serialDir.resolve("delta-1.xml"))
      Files.setLastModifiedTime(delta, fileTime)
      val snapshot = Files.createFile(serialDir.resolve("snapshot-1.xml"))
      Files.setLastModifiedTime(snapshot, fileTime)
      Seq(delta, snapshot)
    }).flatten :+ Files.createFile(rootDir.resolve("notification.xml"))
  }

  def setupDeltas(): (UUID, List[Long]) = {
    val sessionId = UUID.randomUUID()
    val sessionDir = Files.createDirectory(rootDir.resolve(sessionId.toString))
    val serials = 1L to 10L
    serials.foreach { serial =>
      val serialDir = Files.createDirectory(sessionDir.resolve(serial.toString))
      Files.createFile(serialDir.resolve("delta-1.xml"))
    }
    (sessionId, serials.toList)
  }

  def mtimeIsBefore(f: Path, deleteTimestamp: Long): Boolean = {
    Files.getLastModifiedTime(f).toMillis <= deleteTimestamp
  }
}
