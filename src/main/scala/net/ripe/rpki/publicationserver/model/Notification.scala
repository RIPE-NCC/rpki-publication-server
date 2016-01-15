package net.ripe.rpki.publicationserver.model

import java.util.UUID

import net.ripe.rpki.publicationserver._

import scala.xml.{Elem, Node}

case class SnapshotLocator(uri: String, hash: Hash)

case class DeltaLocator(serial: BigInt, uri: String, hash: Hash)

// TODO Merge it with ChangeSet
case class Notification(sessionId: UUID, serial: BigInt, snapshot: SnapshotLocator, deltas: Iterable[DeltaLocator]) {

  def serialize = notificationXml (
    sessionId,
    serial,
    <snapshot uri={snapshot.uri} hash={snapshot.hash.hash}/>,
    deltas.map { d =>
      val DeltaLocator(serial, uri, hash) = d
        <delta serial={serial.toString()} uri={uri} hash={hash.hash}/>
    }
  )

  private def notificationXml(sessionId: UUID, serial: BigInt, snapshot: Node, deltas: Iterable[Node]): Elem =
    <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.toString} serial={serial.toString()}>
      {snapshot}
      {deltas}
    </notification>
}

object Notification extends Hashing with Config {

  def create(snapshot: Snapshot, serverState: ServerState, deltas: Seq[Delta]): Notification = {
    val snapshotLocator = SnapshotLocator(snapshotUrl(serverState), snapshot.contentHash)
    val deltaLocators = deltas.sortBy(-_.serial).par.map { d =>
      DeltaLocator(d.serial, deltaUrl(d), d.contentHash)
    }.seq
    val ServerState(sessionId, serial) = serverState
    Notification(sessionId, serial, snapshotLocator, deltaLocators)
  }

  def create2(snapshot: Snapshot, serverState: ServerState, deltas: Seq[(Long, Hash)]): Notification = {
    val snapshotLocator = SnapshotLocator(snapshotUrl(serverState), snapshot.contentHash)
    val deltaLocators = deltas.sortBy(-_._1).map { d =>
      val (serial, hash) = d
      DeltaLocator(serial, deltaUrl(snapshot.serverState.sessionId, serial), hash)
    }.seq
    val ServerState(sessionId, serial) = serverState
    Notification(sessionId, serial, snapshotLocator, deltaLocators)
  }

}
