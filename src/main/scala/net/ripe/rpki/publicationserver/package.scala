package net.ripe.rpki

import com.softwaremill.macwire._

import java.nio.file.attribute.{FileAttribute, PosixFilePermission}

package object publicationserver {

  trait RepositoryPath {
    val repositoryPath = AppConfig.validate(wire[AppConfig]).rrdpRepositoryPath
  }

  type FileAttributes = FileAttribute[java.util.Set[PosixFilePermission]]
  type FilePermissions = java.util.Set[PosixFilePermission]
}
