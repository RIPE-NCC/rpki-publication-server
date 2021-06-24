package net.ripe.rpki.publicationserver.fs

import java.nio.file.{FileVisitResult, Files, Path, Paths}
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import net.ripe.rpki.publicationserver.PublicationServerBaseTest
import net.ripe.rpki.publicationserver.fs.RrdpRepositoryWriter.snapshotToDelete

class RemovingFileVisitorTest extends PublicationServerBaseTest {

  val someFilename = Paths.get("somename")
  val someTimestamp = FileTime.fromMillis(1)


  test("isCreatedBefore should return true for file older than timestamp") {
    val file = tempFile
    val subject = new RemovingFileVisitor(f =>
      f.getFileName == file.getFileName && snapshotToDelete(timestampAfter(file), 0)(f))

    FSUtil.isCreatedBefore(file, timestampAfter(file)) should be(true)
  }

  test("isCreatedBefore should return false for file yonger than timestamp") {
    val file = tempFile
    val subject = new RemovingFileVisitor(f =>
      f.getFileName == file.getFileName && snapshotToDelete(timestampBefore(file), 0)(f))

    FSUtil.isCreatedBefore(file, timestampBefore(file)) should be(false)
  }

  test("isEmptyDir should return true for empty dir") {
    val dir = tempDirectory
    FSUtil.isEmptyDir(dir) should be(true)
  }

  test("isEmptyDir should return false for non-empty dir") {
    val dir = tempDirectory
    Files.createFile(dir.resolve("filler")).toFile.deleteOnExit()
    FSUtil.isEmptyDir(dir) should be(false)
  }

  test("should not remove other old files") {
    val file = tempFile
    val subject = new RemovingFileVisitor(f =>
        f.getFileName == someFilename && snapshotToDelete(timestampAfter(file), 0)(f))

    subject.visitFile(file, Files.readAttributes(file, classOf[BasicFileAttributes])) should be(FileVisitResult.CONTINUE)

    Files.exists(file) should be(true)
  }

  test("should not remove old file in case it has passed serial number in it") {
    val file = {
      val directory = Files.createTempDirectory("test_remove_visitor")
      deleteOnExit(directory)

      Files.createDirectory(Paths.get(directory.toString, "1"))
      Files.createDirectory(Paths.get(directory.toString, "1", "snapshot.xml"))
    }
    val subject = new RemovingFileVisitor(f =>
        f.getFileName == file.getFileName && snapshotToDelete(timestampAfter(file), 1)(f))

    subject.visitFile(file, Files.readAttributes(file, classOf[BasicFileAttributes])) should be(FileVisitResult.CONTINUE)

    Files.exists(file) should be(true)
  }


  def timestampAfter(file: Path): FileTime = {
    FileTime.from(Files.getLastModifiedTime(file).toInstant.plusSeconds(10))
  }

  def timestampBefore(file: Path): FileTime = {
    FileTime.from(Files.getLastModifiedTime(file).toInstant.minusSeconds(10))
  }

  def tempDirectory: Path = {
    val directory: Path = Files.createTempDirectory("test_remove_dir")
    deleteOnExit(directory)
    directory
  }

  def tempFile: Path = {
    val file: Path = Files.createTempFile("test_remove_file", "")
    deleteOnExit(file)
    file
  }

}
