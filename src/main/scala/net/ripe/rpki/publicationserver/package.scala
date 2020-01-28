package net.ripe.rpki

import com.softwaremill.macwire._

package object publicationserver {

  case class Base64(value: String)

  trait RepositoryPath {
    val repositoryPath = wire[AppConfig].rrdpRepositoryPath
  }
}
