package net.ripe.rpki.publicationserver.store.fs

import java.nio.file.{Paths, FileVisitResult, Path, Files}
import java.nio.file.attribute.{BasicFileAttributes, FileTime}

import net.ripe.rpki.publicationserver.PublicationServerBaseTest

class RemovingFileVisitorTest extends PublicationServerBaseTest {

  val someFilename = Paths.get("somename")
  val someTimestamp = FileTime.fromMillis(1)


  test("isCreatedBefore should return true for file older than timestamp") {
    val file = tempFile
    val subject = new RemovingFileVisitor(timestampAfter(file), file.getFileName, 0)

    subject.isCreatedBefore(file) should be(true)
  }

  test("isCreatedBefore should return false for file yonger than timestamp") {
    val file = tempFile
    val subject = new RemovingFileVisitor(timestampBefore(file), file.getFileName, 0)

    subject.isCreatedBefore(file) should be(false)
  }

  test("isEmptyDir should return true for empty dir") {
    val dir = tempDirectory
    val subject = new RemovingFileVisitor(someTimestamp, someFilename, 0)
    subject.isEmptyDir(dir) should be(true)
  }

  test("isEmptyDir should return false for non-empty dir") {
    val dir = tempDirectory
    Files.createFile(dir.resolve("filler")).toFile.deleteOnExit()
    val subject = new RemovingFileVisitor(someTimestamp, someFilename, 0)
    subject.isEmptyDir(dir) should be(false)
  }

  test("should not remove other old files") {
    val file = tempFile
    val subject = new RemovingFileVisitor(timestampAfter(file), someFilename, 0)

    subject.visitFile(file, Files.readAttributes(file, classOf[BasicFileAttributes])) should be(FileVisitResult.CONTINUE)

    Files.exists(file) should be(true)
  }

  test("should remove old file") {
    val file = tempFile
    val subject = new RemovingFileVisitor(timestampAfter(file), file.getFileName, 0)

    subject.visitFile(file, Files.readAttributes(file, classOf[BasicFileAttributes])) should be(FileVisitResult.CONTINUE)

    Files.exists(file) should be(false)
  }

  test("should not remove old file in case it has passed serial number in it") {
    val file = {
      val directory = Files.createTempDirectory("test")
      directory.toFile.deleteOnExit()
      Files.createDirectory(Paths.get(directory.toString, "1"))
      Files.createDirectory(Paths.get(directory.toString, "1", "snapshot.xml"))
    }
    val subject = new RemovingFileVisitor(timestampAfter(file), file.getFileName, 1)

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
    val directory: Path = Files.createTempDirectory("test")
    directory.toFile.deleteOnExit()
    directory
  }

  def tempFile: Path = {
    val file: Path = Files.createTempFile("test", "")
    file.toFile.deleteOnExit()
    file
  }

}
