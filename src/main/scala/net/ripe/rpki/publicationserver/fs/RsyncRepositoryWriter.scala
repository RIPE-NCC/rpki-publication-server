package net.ripe.rpki.publicationserver.fs

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import org.apache.commons.io.FileUtils

case class RsyncFsLocation(base: Path, relative: Path)

class RsyncRepositoryWriter(conf: AppConfig) extends Logging {

  val directoryPermissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(conf.rsyncDirectoryPermissions))
  val filePermissions = PosixFilePermissions.fromString(conf.rsyncFilePermissions)

  val tempDirPrefix = "temp-"

  logger.info(s"Using following URL mapping:\n${conf.rsyncRepositoryMapping}")

  private def writeObjectUnderDir(bytes: Bytes, baseDir: Path, relative: Path): Unit = {
    val file: Path = baseDir.resolve(relative)
    createParentDirectories(file)
    writeToFile(bytes, file)
  }

  private def promoteStagingToOnline(tempRepoDir: Path): Unit = {
    val target: Path = tempRepoDir.getParent.resolveSibling(conf.rsyncRepositoryOnlineDirName)
    FileUtils.deleteDirectory(target.toFile)
    Files.move(tempRepoDir, target, StandardCopyOption.ATOMIC_MOVE)
    logger.info(s"Created new repo layout at $target")
  }

  private def resolvePath(uri: URI): RsyncFsLocation = {
    conf.rsyncRepositoryMapping.collectFirst {
      case (rootUri, baseDir) if !rootUri.relativize(uri).isAbsolute =>
        RsyncFsLocation(baseDir, Paths.get(rootUri.relativize(uri).toString))
    } match {
    case Some(rsyncFsLocation) => rsyncFsLocation
    case None => throw new IllegalArgumentException(s"Unable to map URI to filesystem location: $uri")
    }
  }

  def startSnapshot: Map[Path, Path] =
    conf.rsyncRepositoryMapping.map {
      case (_, baseDir) => (baseDir, createTempRepoDir(baseDir))
    }

  def writeFileInSnapshot(uri: URI, bytes: Bytes, tempDirs: Map[Path, Path]): Unit = {
    val RsyncFsLocation(base, relative) = resolvePath(uri)
    writeObjectUnderDir(bytes, tempDirs(base), relative)
  }

  def promoteAllStagingToOnline(tempDirs: Map[Path, Path]) =
    tempDirs.foreach { case (_, tmpDir) =>
      try {
        promoteStagingToOnline(tmpDir)
      } finally {
        FileUtils.deleteDirectory(tmpDir.toFile)
      }
    }

  def writeFile(uri: URI, bytes: Bytes): Unit = {
    val fsLocation = resolvePath(uri)

    val stagingDir: Path = stagingDirFor(fsLocation.base)
    Files.createDirectories(stagingDir)
    val tempFile: Path = Files.createTempFile(stagingDir, fsLocation.relative.getFileName.toString, ".tmp")
    writeToFile(bytes, tempFile)

    val targetFile: Path = onlineFileFor(fsLocation)
    createParentDirectories(targetFile)
    Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE)
    logger.debug(s"Written $targetFile")
  }

  def removeFile(uri: URI): Unit = {
    val target = onlineFileFor(resolvePath(uri))
    if (Files.deleteIfExists(target)) {
      logger.info(s"Deleted $target")
    }
    else logger.warn(s"File to delete ($target) does not exist")
  }

  private def writeToFile(bytes: Bytes, tempFile: Path): Unit = {
    Files.copy(new ByteArrayInputStream(bytes.value), tempFile, StandardCopyOption.REPLACE_EXISTING)
    Files.setPosixFilePermissions(tempFile, filePermissions)
  }

  private def createParentDirectories(targetFile: Path): Path = {
    Files.createDirectories(targetFile.getParent, directoryPermissions)
  }

  private def onlineFileFor(fsLocation: RsyncFsLocation): Path = {
    onlineDirFor(fsLocation.base).resolve(fsLocation.relative)
  }

  private def createTempRepoDir(baseDir: Path): Path = {
    val parentDir: Path = stagingDirFor(baseDir)
    Files.createDirectories(parentDir, directoryPermissions)
    Files.createTempDirectory(parentDir, tempDirPrefix, directoryPermissions)
  }

  private def stagingDirFor(base: Path): Path = base.resolve(conf.rsyncRepositoryStagingDirName)
  private def onlineDirFor(base: Path): Path = base.resolve(conf.rsyncRepositoryOnlineDirName)
}
