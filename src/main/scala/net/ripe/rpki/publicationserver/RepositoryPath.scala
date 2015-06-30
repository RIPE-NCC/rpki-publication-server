package net.ripe.rpki.publicationserver

import com.softwaremill.macwire.MacwireMacros._

trait RepositoryPath {
  val repositoryPath = wire[AppConfig].locationRepositoryPath
}
