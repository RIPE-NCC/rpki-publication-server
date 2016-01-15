package net.ripe.rpki.publicationserver

import java.util.UUID

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model.{Delta, ServerState}

trait Config {
  lazy val conf = wire[AppConfig]

  lazy val repositoryUri = conf.rrdpRepositoryUri

  def snapshotUrl(serverState: ServerState) = {
    val ServerState(sessionId, serial) = serverState
    repositoryUri + "/" + sessionId + "/" + serial + "/snapshot.xml"
  }

  def deltaUrl(delta: Delta) : String = deltaUrl(delta.sessionId, delta.serial)

  def deltaUrl(sessionId: UUID, serial: Long) = repositoryUri + "/" + sessionId + "/" + serial + "/delta.xml"
}
