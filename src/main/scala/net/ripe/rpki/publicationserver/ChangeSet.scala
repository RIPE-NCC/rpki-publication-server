package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.UUID

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.store.{DB, ClientId, ObjectStore}
import net.ripe.rpki.publicationserver.store.fs.RepositoryWriter

import scala.util.{Failure, Success}
import scala.xml.{Elem, Node}

case class ChangeSet(sessionId: UUID, serial: BigInt, deltas: Map[BigInt, Delta]) extends Hashing {

  def next(queries: Seq[QueryPdu]): ChangeSet = {
    val newSerial = serial + 1
    val newDeltas = deltas + (newSerial -> Delta(sessionId, newSerial, queries))
    ChangeSet(sessionId, newSerial, newDeltas)
  }

  def latestDelta = {
    deltas.get(serial)
  }

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
object ChangeSet extends SnapshotStateUpdater {

  type SnapshotMap = Map[URI, (Base64, Hash)]

}

trait SnapshotStateUpdater extends Urls with Logging with Hashing {

  val sessionId = conf.currentSessionId

  val repositoryWriter = wire[RepositoryWriter]

  val notificationState = wire[NotificationStateUpdater]

  val db = wire[ObjectStore]

  private var changeSet = emptySnapshot

  def emptySnapshot = new ChangeSet(conf.currentSessionId, BigInt(1), Map.empty)

  def get = changeSet

  def initializeWith(initState: ChangeSet) = {
    changeSet = initState
  }

  def list(clientId: ClientId) = db.list(clientId).map { pdu =>
    val (base64, hash, uri) = pdu
    ListR(uri, hash.hash, None)
  }

  def updateWith(clientId: ClientId, queries: Seq[QueryPdu]): Seq[ReplyPdu] = synchronized {
    persistForClient(clientId, queries) { replies : Seq[ReplyPdu] =>
      val newChangeSet = changeSet.next(queries)
      val pdus = db.listAll
      val snapshotXml = serialize(newChangeSet, pdus).mkString
      val newNotification = Notification.create(snapshotXml, newChangeSet)
      repositoryWriter.writeNewState(conf.locationRepositoryPath, newChangeSet, newNotification, snapshotXml) match {
        case Success(_) =>
          changeSet = newChangeSet
          notificationState.update(newNotification)
          Seq()
        case Failure(e) =>
          logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
          Seq(ReportError(BaseError.CouldNotPersist, Some("Could not write XML files to filesystem: " + e.getMessage)))
      }
    }
  }

//  def writeRepositoryState(newSnapshot: ChangeSet) = {
//    val newNotification = Notification.create(newSnapshot)
//    repositoryWriter.writeNewState(conf.locationRepositoryPath, newSnapshot, newNotification).map { _ =>
//      notificationState.update(newNotification)
//    }
//  }

  def serialize(changeSet: ChangeSet, pdus: Seq[DB.RRDPObject]) = snapshotXml(
    changeSet.sessionId,
    changeSet.serial,
    pdus.map { e =>
      val (base64, hash, uri) = e
      <publish uri={uri.toString} hash={hash.hash}>
        {base64.value}
      </publish>
    }
  )

  private def snapshotXml(sessionId: UUID, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <snapshot xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId.toString} serial={serial.toString()}>
      {pdus}
    </snapshot>

  /*
   * TODO Check if the client doesn't try to modify objects that belong to other client
   */
  def persistForClient(clientId: ClientId, queries: Seq[QueryPdu])(f : Seq[ReplyPdu] => Seq[ReplyPdu]) = {
    val result = queries.map {
      case PublishQ(uri, tag, None, base64) =>
        db.find(uri) match {
          case Some((_, h, _)) =>
            Left(ReportError(BaseError.HashForInsert, Some(s"Tried to insert existing object [$uri].")))
          case None =>
            Right((PublishR(uri, tag), db.insertAction(clientId, (base64, hash(base64), uri))))
        }
      case PublishQ(uri, tag, Some(sHash), base64) =>
        db.find(uri) match {
          case Some(obj) =>
            val (_, h, _) = obj
            if (Hash(sHash) == h)
              Right((PublishR(uri, tag), db.updateAction(clientId, (base64, h, uri))))
            else {
              Left(ReportError(BaseError.NonMatchingHash, Some(s"Cannot republish the object [$uri], hash doesn't match")))
            }
          case None =>
            Left(ReportError(BaseError.NoObjectToUpdate, Some(s"No object [$uri] has been found.")))
        }

      case WithdrawQ(uri, tag, sHash) =>
        db.find(uri) match {
          case Some(obj) =>
            val (_, h, _) = obj
            if (Hash(sHash) == h)
              Right((WithdrawR(uri, tag), db.deleteAction(clientId, h)))
            else {
              Left(ReportError(BaseError.NonMatchingHash, Some(s"Cannot withdraw the object [$uri], hash doesn't match.")))
            }
          case None =>
            Left(ReportError(BaseError.NoObjectForWithdraw, Some(s"No object [$uri] found.")))
        }
    }

    val errors = result.collect { case Left(error) => error }
    if (errors.nonEmpty) {
      errors
    }
    else {
      val actions = result.collect { case Right((_, action)) => action }
      lazy val replies = result.collect { case Right((reply, _)) => reply }
      try {
        val transactionReplies = db.atomic(actions, f(replies))
        replies ++ transactionReplies
      } catch {
        case e: Exception =>
          logger.error("Couldn't persis objects", e)
          Seq(ReportError(BaseError.CouldNotPersist, Some(s"A problem occurred while persisting the changes: " + e)))
      }
    }
  }

}


