package net.ripe.rpki.publicationserver.model

import java.util.UUID

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver._

import scala.xml.{Node, Elem}

case class SnapshotLocator(uri: String, hash: Hash)

case class DeltaLocator(serial: BigInt, uri: String, hash: Hash)

// TODO Merge it with ChangeSet
case class Notification(sessionId: UUID, serial: BigInt, snapshot: SnapshotLocator, deltas: Iterable[DeltaLocator]) {

  lazy val serialized = serialize.mkString

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

object Notification extends Hashing with Urls {

  def create(snapshot: Snapshot, serverState: ServerState, deltas: Seq[Delta]): Notification = {
    val snapshotLocator = SnapshotLocator(snapshotUrl(serverState), snapshot.contentHash)
    val deltaLocators = deltas.map { d =>
      DeltaLocator(d.serial, deltaUrl(d), d.contentHash)
    }
    val ServerState(sessionId, serial) = serverState
    Notification(sessionId, serial, snapshotLocator, deltaLocators)
  }
}
