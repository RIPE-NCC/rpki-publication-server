package net.ripe.rpki.publicationserver.store.fs

import akka.actor.{Actor, Props}
import net.ripe.rpki.publicationserver.model.{Delta, ServerState}
import net.ripe.rpki.publicationserver.store.DeltaStore
import net.ripe.rpki.publicationserver.{Logging, Urls}
import com.softwaremill.macwire.MacwireMacros._

case class CleanCommand(newServerState: ServerState, deltas: Seq[Delta])

class DeltaCleanActor extends Actor with Logging with Urls {

  // TODO Share the same cache between different referencing parties
  private val deltaStore = wire[DeltaStore]

  private val repositoryWriter = wire[RepositoryWriter]

  override def receive = {
    case CleanCommand(newServerState, deltas) =>
      logger.info("Removing deltas from DB and filesystem")
      deltaStore.delete(deltas)
      repositoryWriter.deleteDeltas(conf.locationRepositoryPath, deltas)
  }
}

object DeltaCleanActor {
  def props = Props(new DeltaCleanActor())
}