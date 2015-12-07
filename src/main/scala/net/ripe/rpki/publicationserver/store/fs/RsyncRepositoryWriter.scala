package net.ripe.rpki.publicationserver.store.fs

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardCopyOption}

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.Delta
import net.ripe.rpki.publicationserver.{AppConfig, Base64, Logging, PublishQ, WithdrawQ}

import scala.util.Try

class RsyncRepositoryWriter extends Logging {
  lazy val conf = wire[AppConfig]

  val newDirectoryPermissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxr-x"))

  def resolvePath(uri: URI): Path = {
    conf.rsyncRepositoryMapping.collectFirst {
      case (rootUri,rootPath) if rootUri.relativize(uri) != uri =>
        rootPath.resolve(rootUri.relativize(uri).toString)
    }.get
  }

  def writeFile(uri: URI, base64: Base64): Unit = {
    val inputStream = new ByteArrayInputStream(base64.value.getBytes("UTF-8"))
    val target: Path = resolvePath(uri)
    Files.createDirectories(target.getParent, newDirectoryPermissions)
    Files.copy(java.util.Base64.getDecoder.wrap(inputStream), target, StandardCopyOption.REPLACE_EXISTING)
  }

  def removeFile(uri: URI): Unit = {
    Files.deleteIfExists(resolvePath(uri))
  }

  def writeDelta(rootDir: String, delta: Delta) = Try {
    delta.pdus.foreach {
      case PublishQ(uri, tag, hash, base64) =>
        writeFile(uri, base64)
      case WithdrawQ(uri, tag, hash) =>
        removeFile(uri)
      case unknown => throw new UnsupportedOperationException(s"Unknown PDU in delta: $unknown")
    }
  }
}
