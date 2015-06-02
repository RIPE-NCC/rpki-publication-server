package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.UUID

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.SnapshotState
import net.ripe.rpki.publicationserver.fs.RepositoryWriter

import scala.xml.{Elem, Node}

case class SnapshotState(sessionId: UUID, serial: BigInt, pdus: SnapshotState.SnapshotMap, deltas: Map[BigInt, Delta]) extends Hashing {

  def apply(queries: Seq[QueryPdu]): (Seq[ReplyPdu], Option[SnapshotState]) = {

    var newPdus = pdus
    val replies = queries.map {
      case PublishQ(uri, tag, None, base64) =>
        newPdus.get(uri) match {
          case Some(_) => ReportError(BaseError.HashForInsert, Some(s"Tried to insert existing object [$uri]."))
          case None =>
            newPdus += (uri ->(base64, hash(base64)))
            PublishR(uri, tag)
        }

      case PublishQ(uri, tag, Some(qHash), base64) =>
        newPdus.get(uri) match {
          case Some((_, h)) =>
            if (h == Hash(qHash)) {
              newPdus += (uri ->(base64, hash(base64)))
              PublishR(uri, tag)
            } else
              ReportError(BaseError.NonMatchingHash, Some(s"Cannot republish the object [$uri], hash doesn't match"))

          case None =>
            ReportError(BaseError.NoObjectToUpdate, Some(s"No object [$uri] has been found."))
        }

      case WithdrawQ(uri, tag, qHash) =>
        newPdus.get(uri) match {
          case Some((_, h)) =>
            if (h == Hash(qHash)) {
              newPdus -= uri
              WithdrawR(uri, tag)
            } else
              ReportError(BaseError.NonMatchingHash, Some(s"Cannot withdraw the object [$uri], hash doesn't match."))

          case None =>
            ReportError(BaseError.NoObjectForWithdraw, Some(s"No object [$uri] found."))
        }

      case pdu@_ =>
        ReportError(BaseError.NoObjectForWithdraw, Some(s"Incorrect pdu: $pdu"))
    }

    if (replies.exists(r => r.isInstanceOf[ReportError]))
      (replies, None)
    else {
      val newSerial = serial + 1
      val newDeltas = deltas + (newSerial -> Delta(sessionId, newSerial, queries))
      (replies, Some(SnapshotState(sessionId, newSerial, newPdus, newDeltas)))
    }
  }

  def latestDelta = {
    deltas.get(serial)
  }

  def list = pdus.map { pdu =>
    val (uri, (_, hash)) = pdu
    ListR(uri, hash.hash, None)
  }.toSeq

  def serialize = snapshotXml(
    sessionId,
    serial,
    pdus.map { e =>
      val (uri, (base64, hash)) = e
      <publish uri={uri.toString} hash={hash.hash}>
        {base64.value}
      </publish>
    }
  )

  private def snapshotXml(sessionId: UUID, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <snapshot xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.toString} serial={serial.toString()}>
      {pdus}
    </snapshot>
}

case class Delta(sessionId: UUID, serial: BigInt, pdus: Seq[QueryPdu]) extends Hashing {

  def serialize = deltaXml(
    sessionId,
    serial,
    pdus.map {
      case PublishQ(uri, _, None, base64) => <publish uri={uri.toString}>
        {base64.value}
      </publish>
      case PublishQ(uri, _, Some(hash), base64) => <publish uri={uri.toString} hash={hash}>
        {base64.value}
      </publish>
      case WithdrawQ(uri, _, hash) => <withdraw uri={uri.toString} hash={hash}/>
    }
  )

  private def deltaXml(sessionId: UUID, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <delta xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.toString} serial={serial.toString()}>
      {pdus}
    </delta>

}

/**
 * Holds the global snapshot state
 */
object SnapshotState extends SnapshotStateUpdater {

  type SnapshotMap = Map[URI, (Base64, Hash)]

}

trait SnapshotStateUpdater extends Urls {

  val sessionId = conf.currentSessionId

  val repositoryWriter = wire[RepositoryWriter]

  private var state = emptySnapshot

  def emptySnapshot = new SnapshotState(conf.currentSessionId, BigInt(1), Map.empty, Map.empty)

  def get = state

  def initializeWith(initState: SnapshotState) = {
    state = initState
    val newNotification = Notification.create(sessionId, initState)
    NotificationState.update(newNotification)
  }

  def updateWith(queries: Seq[QueryPdu]): Seq[ReplyPdu] = synchronized {
    val (replies, newState) = state(queries)
    if (newState.isDefined) {
      writeSnapshotAndNotification(newState.get)
      state = newState.get
    }
    replies
  }

  def listReply = get.list

  def writeSnapshotAndNotification(newSnapshot: SnapshotState) = {
    repositoryWriter.writeSnapshot(conf.locationRepositoryPath, newSnapshot)
    newSnapshot.latestDelta match {
      case Some(d) => repositoryWriter.writeDelta(conf.locationRepositoryPath, d)
      case None => // TODO That's unlikely, but still log it
    }
    val newNotification = Notification.create(sessionId, newSnapshot)
    repositoryWriter.writeNotification(conf.locationRepositoryPath, newNotification)
    NotificationState.update(newNotification)
  }

  def writeSnapshotAndNotification(newSnapshot: SnapshotState, delta: Delta) = {
    repositoryWriter.writeSnapshot(conf.locationRepositoryPath, newSnapshot)
    repositoryWriter.writeDelta(conf.locationRepositoryPath, delta)
    val newNotification = Notification.create(sessionId, newSnapshot, Seq(delta))
    repositoryWriter.writeNotification(conf.locationRepositoryPath, newNotification)
    NotificationState.update(newNotification)
  }
}



