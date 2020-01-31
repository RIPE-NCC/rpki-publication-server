package net.ripe.rpki

import com.softwaremill.macwire.MacwireMacros._

package object publicationserver {

  trait RepositoryPath {
    val repositoryPath = wire[AppConfig].rrdpRepositoryPath
  }
}
