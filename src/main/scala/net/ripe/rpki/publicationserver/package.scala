package net.ripe.rpki

import java.nio.file.attribute.{FileAttribute, PosixFilePermission, PosixFilePermissions}

import com.softwaremill.macwire._

package object publicationserver {

  trait RepositoryPath {
    val repositoryPath = wire[AppConfig].rrdpRepositoryPath
  }

  type FileAttributes = FileAttribute[java.util.Set[PosixFilePermission]]
  type FilePermissions = java.util.Set[PosixFilePermission]

  implicit def into(x: FilePermissions): FileAttributes =
    PosixFilePermissions.asFileAttribute(x)
}
