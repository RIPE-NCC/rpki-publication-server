package net.ripe.rpki.publicationserver.store.fs

import java.io.IOException
import java.nio.file._
import java.nio.file.attribute.{BasicFileAttributes, FileTime}

import net.ripe.rpki.publicationserver.Logging

class RemovingFileVisitor(timestamp: FileTime, filenameToDelete: Path, latestSerial: Long) extends SimpleFileVisitor[Path] with Logging {

  val latestFileNameComponent = Paths.get(latestSerial.toString, filenameToDelete.toString)

  override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
    logger.error(s"Error visiting $file: ${exc.getMessage}")
    if (Files.isDirectory(file)) FileVisitResult.SKIP_SUBTREE
    else FileVisitResult.CONTINUE
  }

  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    if (file.endsWith(filenameToDelete) && !file.endsWith(latestFileNameComponent) && FSUtil.isModifiedBefore(file, timestamp)) {
      logger.info(s"Removing $file")
      Files.deleteIfExists(file)
    }
    FileVisitResult.CONTINUE
  }

  override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
    if (FSUtil.isEmptyDir(dir) && FSUtil.isCreatedBefore(dir, timestamp)) {
      logger.info(s"Removing directory $dir")
      Files.deleteIfExists(dir)
    }
    FileVisitResult.CONTINUE
  }

}

class RemoveAllVisitorExceptOneSession(sessionId: String, timestamp: FileTime) extends SimpleFileVisitor[Path] with Logging {
  def oldEnough(path: Path): Boolean = FSUtil.isModifiedBefore(path, timestamp)

  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    if (file.toString.contains(sessionId) || file.toString.endsWith(Rrdp.notificationFilename))
      FileVisitResult.SKIP_SUBTREE
    else {
      if (oldEnough(file)) {
        logger.info(s"Removing file $file")
        Files.deleteIfExists(file)
      }
      FileVisitResult.CONTINUE
    }
  }

  override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
    if (dir.toString.contains(sessionId))
      FileVisitResult.SKIP_SUBTREE
    else {
      if (FSUtil.isEmptyDir(dir)) {
        logger.info(s"Removing directory $dir")
        Files.deleteIfExists(dir)
      }
      FileVisitResult.CONTINUE
    }
  }
}

private object FSUtil {
  def isEmptyDir(dir: Path): Boolean = {
    val stream = Files.newDirectoryStream(dir)
    try {
      !stream.iterator().hasNext
    } finally {
      stream.close()
    }
  }

  def isModifiedBefore(file: Path, timestamp: FileTime): Boolean = {
    Files.getLastModifiedTime(file).compareTo(timestamp) <= 0
  }

  def isCreatedBefore(path: Path, timestamp: FileTime): Boolean = {
    Files.readAttributes(path, classOf[BasicFileAttributes]).creationTime.compareTo(timestamp) <= 0
  }
}
