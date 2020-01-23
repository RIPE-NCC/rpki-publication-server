package net.ripe.rpki.publicationserver.store.fs

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import net.ripe.rpki.publicationserver
import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver._
import org.apache.commons.io.FileUtils

import scala.util.Try

case class RsyncFsLocation(base: Path, relative: Path)

class RsyncRepositoryWriter(conf: AppConfig) extends Logging {

  val directoryPermissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(conf.rsyncDirectoryPermissions))
  val filePermissions = PosixFilePermissions.fromString(conf.rsyncFilePermissions)

  logger.info(s"Using following URL mapping:\n${conf.rsyncRepositoryMapping}")

  def writeSnapshot(state: ObjectStore.State) = {
    val objectsPerBaseDir = groupByBaseDir(state)
    for (baseDir <- objectsPerBaseDir.keys) {
      val tempRepoDir = createTempRepoDir(baseDir)
      Try {
        for (obj <- objectsPerBaseDir(baseDir)) {
          val (base64, rsyncFsLocation) = obj
          writeObjectUnderDir(base64, tempRepoDir, rsyncFsLocation.relative)
        }
        promoteStagingToOnline(tempRepoDir)
      }.recover { case e =>
        FileUtils.deleteDirectory(tempRepoDir.toFile)
      }.get
    }
  }

  def updateRepo(message: QueryMessage) = {
    message.pdus.foreach {
      case PublishQ(uri, tag, hash, base64) =>
        writeFile(uri, base64)
      case WithdrawQ(uri, tag, hash) =>
        removeFile(uri)
      case unknown =>
        throw new UnsupportedOperationException(s"Unknown PDU in ValidatedMesage: $unknown")
    }
  }

  private def groupByBaseDir(state: ObjectStore.State): Map[Path, Seq[(Bytes, RsyncFsLocation)]] = {
    state.toSeq.map {
      case (uri, (base64, _, _)) => (base64, resolvePath(uri))
    }.groupBy(_._2.base)
  }

  private def writeObjectUnderDir(binary: Bytes, baseDir: Path, relative: Path): Unit = {
    val file: Path = baseDir.resolve(relative)
    createParentDirectories(file)
    writeToFile(binary, file)
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

  private def writeFile(uri: URI, binary: Bytes): Unit = {
    val fsLocation = resolvePath(uri)

    val stagingDir: Path = stagingDirFor(fsLocation.base)
    Files.createDirectories(stagingDir)
    val tempFile: Path = Files.createTempFile(stagingDir, fsLocation.relative.getFileName.toString, ".tmp")
    writeToFile(binary, tempFile)

    val targetFile: Path = onlineFileFor(fsLocation)
    createParentDirectories(targetFile)
    Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE)
    logger.info(s"Written $targetFile")
  }

  private def removeFile(uri: URI): Unit = {
    val target = onlineFileFor(resolvePath(uri))
    if (Files.deleteIfExists(target)) logger.info(s"Deleted $target")
    else logger.warn(s"File to delete ($target) does not exist")
  }

  private def writeToFile(binary: Bytes, tempFile: Path): Unit = {
    Files.copy(new ByteArrayInputStream(binary.value), tempFile, StandardCopyOption.REPLACE_EXISTING)
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
    Files.createTempDirectory(parentDir, "temp-", directoryPermissions)
  }

  private def stagingDirFor(base: Path): Path = base.resolve(conf.rsyncRepositoryStagingDirName)
  private def  onlineDirFor(base: Path): Path = base.resolve(conf.rsyncRepositoryOnlineDirName)
}
