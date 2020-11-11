package net.ripe.rpki

import com.softwaremill.macwire._

package object publicationserver {

  trait RepositoryPath {
    val repositoryPath = wire[AppConfig].rrdpRepositoryPath
  }

}
