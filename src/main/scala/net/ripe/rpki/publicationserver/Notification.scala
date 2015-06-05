package net.ripe.rpki.publicationserver

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.softwaremill.macwire.MacwireMacros._

import scala.annotation.tailrec
import scala.collection.immutable
import scala.io.Source
import scala.xml.{Elem, Node}

case class SnapshotLocator(uri: String, hash: Hash)

case class DeltaLocator(serial: BigInt, uri: String, hash: Hash)

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
  
  def snapshotUrl(snapshot: RepositoryState) = repositoryUri + "/" + snapshot.sessionId + "/" + snapshot.serial + "/snapshot.xml"
  def deltaUrl(delta: Delta) = repositoryUri + "/" + delta.sessionId + "/" + delta.serial + "/delta.xml"
}

object Notification extends Hashing with Urls {

  def create(sessionId: UUID, snapshot: RepositoryState): Notification = {
    val snapshotLocator = SnapshotLocator(snapshotUrl(snapshot), hash(snapshot.serialize.mkString.getBytes))
    val deltaLocators = snapshot.deltas.values.map { d =>
      DeltaLocator(d.serial, deltaUrl(d), hash(d.serialize.mkString.getBytes))
    }
    Notification(snapshot.sessionId, snapshot.serial, snapshotLocator, deltaLocators)
  }
}

object NotificationState {
  private val state: AtomicReference[Notification] = new AtomicReference[Notification]()

  def get = state.get()

  def update(notification: Notification): Unit = state.set(notification)
}

object NotificationParser extends MessageParser[Notification] {

  override val Schema = Source.fromURL(getClass.getResource("/rrdp-schema.rng")).mkString

  override protected def parse(parser: StaxParser): Either[BaseError, Notification] = {

    def captureNotificationParameters(attrs: Map[String, String]): (UUID, BigInt) = {
      assert(attrs("version") == "1", "The version attribute in the notification root element MUST be 1")
      val sessionId = UUID.fromString(attrs("session_id"))
      val serial = BigInt(attrs("serial"))
      assert(serial > 0, "The serial attribute must be an unbounded, unsigned positive integer")
      (sessionId, serial)
    }

    @tailrec
    def parseNext(sessionId: UUID, serial: BigInt, snapshotLocator: SnapshotLocator): Either[BaseError, Notification] = {

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
          if ("notification" == label.toLowerCase) Right(Notification(sessionId, serial, snapshotLocator, Seq()))
          else parseNext(sessionId, serial, snapshotLocator)

        case _ =>
          parseNext(sessionId, serial, snapshotLocator)
      }
    }

    parseNext(null, null, null)
  }
}

