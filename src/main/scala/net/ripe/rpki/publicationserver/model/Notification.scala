package net.ripe.rpki.publicationserver.model

import java.util.UUID

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.store.DB.ServerState
import net.ripe.rpki.publicationserver._

import scala.xml.{Node, Elem}

case class SnapshotLocator(uri: String, hash: Hash)

case class DeltaLocator(serial: BigInt, uri: String, hash: Hash)

// TODO Merge it with ChangeSet
case class Notification(sessionId: UUID, serial: BigInt, snapshot: SnapshotLocator, deltas: Iterable[DeltaLocator]) {

  def serialize = notificationXml (
    sessionId,
    serial,
    snapshotXml(snapshot),
    deltas.map { d =>
      val DeltaLocator(serial, uri, hash) = d
        <delta serial={serial.toString()} uri={uri} hash={hash.hash}/>
    }
  )

  private def snapshotXml(snapshot: SnapshotLocator): Elem =
      <snapshot uri={snapshot.uri} hash={snapshot.hash.hash}/>

  private def notificationXml(sessionId: UUID, serial: BigInt, snapshot: Node, deltas: Iterable[Node]): Elem =
    <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.toString} serial={serial.toString()}>
      {snapshot}
      {deltas}
    </notification>
}


trait Urls {
  lazy val conf = wire[ConfigWrapper]

  lazy val repositoryUri = conf.locationRepositoryUri

  def snapshotUrl(serverState: ServerState) = {
    val ServerState(sessionId, serial) = serverState
    repositoryUri + "/" + sessionId + "/" + serial + "/snapshot.xml"
  }
  def deltaUrl(delta: Delta) = repositoryUri + "/" + delta.sessionId + "/" + delta.serial + "/delta.xml"
}

object Notification extends Hashing with Urls {

  def create(snapshotXml: String, serverState: ServerState, snapshot: ChangeSet): Notification = {
    val snapshotLocator = SnapshotLocator(snapshotUrl(serverState), hash(snapshotXml.getBytes))
    val deltaLocators = snapshot.deltas.values.map { d =>
      DeltaLocator(d.serial, deltaUrl(d), hash(d.serialize.mkString.getBytes))
    }
    val ServerState(sessionId, serial) = serverState
    Notification(UUID.fromString(sessionId), serial, snapshotLocator, deltaLocators)
  }
}
