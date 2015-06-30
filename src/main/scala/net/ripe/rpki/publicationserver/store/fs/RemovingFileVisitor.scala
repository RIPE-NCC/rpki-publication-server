package net.ripe.rpki.publicationserver.store.fs

import java.io.IOException
import java.nio.file.{Files, FileVisitResult, Path, SimpleFileVisitor}
import java.nio.file.attribute.{BasicFileAttributes, FileTime}

import net.ripe.rpki.publicationserver.Logging

class RemovingFileVisitor(timestamp: FileTime, filenameToDelete: Path) extends SimpleFileVisitor[Path] with Logging {

  override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
    logger.error(s"Error visiting $file: ${exc.getMessage}")
    if (Files.isDirectory(file)) FileVisitResult.SKIP_SUBTREE
    else FileVisitResult.CONTINUE
  }

  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    if (file.endsWith(filenameToDelete) && isModifiedBefore(file)) {
      logger.info(s"Removing $file")
      Files.deleteIfExists(file)
    }
    FileVisitResult.CONTINUE
  }

  override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
    if (isEmptyDir(dir) && isCreatedBefore(dir)) {
      logger.info(s"Removing directory $dir")
      Files.deleteIfExists(dir)
    }
    FileVisitResult.CONTINUE
  }

  def isModifiedBefore(file: Path): Boolean = {
    Files.getLastModifiedTime(file).compareTo(timestamp) < 0
  }

  def isCreatedBefore(path: Path): Boolean = {
    Files.readAttributes(path, classOf[BasicFileAttributes]).creationTime.compareTo(timestamp) < 0
  }

  def isEmptyDir(dir: Path): Boolean = {
    val stream = Files.newDirectoryStream(dir)
    try {
      ! stream.iterator().hasNext
    } finally {
      stream.close()
    }
  }
}
