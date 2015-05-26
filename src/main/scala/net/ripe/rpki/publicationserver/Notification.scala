package net.ripe.rpki.publicationserver

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.softwaremill.macwire.MacwireMacros._

import scala.annotation.tailrec
import scala.io.Source
import scala.xml.{Elem, Node}

case class SnapshotLocator(uri: String, hash: Hash)

case class Delta(serial: BigInt, uri: String, hash: Hash)

case class Notification(sessionId: UUID, serial: BigInt, snapshot: SnapshotLocator, deltas: Seq[Delta]) {

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

  private def notificationXml(sessionId: UUID, serial: BigInt, snapshot: Node, deltas: Iterable[Node]): Elem =
    <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.toString} serial={serial.toString()}>
      {snapshot}
      {deltas}
    </notification>
}

object Notification extends Hashing {

  def fromSnapshot(sessionId: UUID, uri: String, snapshot: SnapshotState): Notification = {
    val locator = SnapshotLocator(uri, hash(snapshot.serialize.mkString.getBytes))
    Notification(sessionId, snapshot.serial, locator, Seq())
  }
}

object NotificationState {
  private val state: AtomicReference[Notification] = new AtomicReference[Notification]()

  def get = state.get()

  def update(sessionId: UUID, uri: String, snapshot: SnapshotState): Unit = state.set(Notification.fromSnapshot(sessionId, uri, snapshot))
}

object NotificationParser extends MessageParser[Notification] {

  override val Schema = Source.fromURL(getClass.getResource("/rrdp-schema.rng")).mkString

  override def parse(parser: StaxParser): Notification = {

    def captureNotificationParameters(attrs: Map[String, String]): (UUID, BigInt) = {
      assert(attrs("version") == "1", "The version attribute in the notification root element MUST be 1")
      val sessionId = UUID.fromString(attrs("session_id"))
      val serial = BigInt(attrs("serial"))
      assert(serial > 0, "The serial attribute must be an unbounded, unsigned positive integer")
      (sessionId, serial)
    }

    @tailrec
    def parseNext(sessionId: UUID, serial: BigInt, snapshotLocator: SnapshotLocator): Notification = {

      assert(parser.hasNext, s"The notification.xml file does not contain a complete 'notification' element")

      parser.next match {
        case ElementStart(label, attrs) =>

          label.toLowerCase match {
            case "notification" =>
              val p = captureNotificationParameters(attrs)
              parseNext(p._1, p._2, snapshotLocator)
            case "snapshot" =>
              parseNext(sessionId, serial, SnapshotLocator(uri=attrs("uri"), hash=Hash(attrs("hash"))))
            case "delta" =>
              parseNext(sessionId, serial, snapshotLocator)
          }

        case ElementEnd(label) =>
          if ("notification" == label.toLowerCase) Notification(sessionId, serial, snapshotLocator, Seq())
          else parseNext(sessionId, serial, snapshotLocator)

        case _ =>
          parseNext(sessionId, serial, snapshotLocator)
      }
    }

    parseNext(null, null, null)
  }
}

