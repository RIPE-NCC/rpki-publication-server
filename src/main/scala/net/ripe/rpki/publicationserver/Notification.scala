package net.ripe.rpki.publicationserver

import java.util.concurrent.atomic.AtomicReference

import scala.xml.{Elem, Node}

case class SnapshotLocator(uri: String, hash: Hash)

case class Delta(serial: BigInt, uri: String, hash: Hash)

case class Notification(sessionId: SessionId, serial: BigInt, snapshot: SnapshotLocator, deltas: Seq[Delta]) {

  def serialize = notificationXml (
    sessionId,
    serial,
    snapshotXml(snapshot),
    deltas.map { d =>
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

object Notification extends Hashing {

  def fromSnapshot(sessionId: SessionId, uri: String, snapshot: SnapshotState): Notification = {
    val locator = SnapshotLocator(uri, hash(snapshot.serialize.mkString.getBytes))
    Notification(sessionId, snapshot.serial, locator, Seq())
  }
}

object NotificationState {
  private val state: AtomicReference[Notification] = new AtomicReference[Notification]()

  def get = state.get()

  def update(sessionId: SessionId, uri: String, snapshot: SnapshotState): Unit = state.set(Notification.fromSnapshot(sessionId, uri, snapshot))
}
