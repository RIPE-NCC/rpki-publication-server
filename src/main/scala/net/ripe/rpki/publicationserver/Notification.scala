package net.ripe.rpki.publicationserver

import scala.xml.{Elem, Node}

case class SnapshotLocator(uri: String, hash: Hash)

case class Delta(serial: BigInt, uri: String, hash: Hash)

case class Notification(sessionId: SessionId, serial: BigInt, snapshot: SnapshotLocator, deltas: Seq[Delta])

object Notification {

  def serialize(notification: Notification) = notificationXml (
    notification.sessionId,
    notification.serial,
    snapshotXml(notification.snapshot),
    notification.deltas.map { d =>
      val Delta(serial, uri, hash) = d
      <delta serial={serial.toString()} uri={uri} hash={hash.hash}/>
    }
  )

  private def snapshotXml(snapshot: SnapshotLocator): Elem =
    <snapshot uri={snapshot.uri} hash={snapshot.hash.hash}/>

  private def notificationXml(sessionId: SessionId, serial: BigInt, snapshot: Node, deltas: Iterable[Node]): Elem =
    <notification xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.id} serial={serial.toString()}>
      {snapshot}
      {deltas}
    </notification>
}