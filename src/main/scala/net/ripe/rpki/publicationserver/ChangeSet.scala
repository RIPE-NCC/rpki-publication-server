package net.ripe.rpki.publicationserver

import java.net.URI

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.store.DB.{DBType, ServerState}
import net.ripe.rpki.publicationserver.store.fs.RepositoryWriter
import net.ripe.rpki.publicationserver.store._

import scala.util.{Failure, Success}
import scala.xml.{Elem, Node}

case class ChangeSet(deltas: Map[Long, Delta]) extends Hashing {

  def next(newServerState: ServerState, queries: Seq[QueryPdu]): ChangeSet = {
    val ServerState(sessionId, newSerial) = newServerState
    val newDeltas = deltas + (newSerial -> Delta(sessionId, newSerial, queries))
    ChangeSet(newDeltas)
  }

  def latestDelta(serial: Long) = deltas.get(serial)
}

case class Delta(sessionId: String, serial: Long, pdus: Seq[QueryPdu]) extends Hashing {

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

  private def deltaXml(sessionId: String, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <delta xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId} serial={serial.toString()}>
      {pdus}
    </delta>

}

case class Snapshot(serverState: ServerState, pdus: Seq[DB.RRDPObject]) {
  
  def serialize = {
    val ServerState(sessionId, serial) = serverState
    snapshotXml(
      sessionId,
      serial,
      pdus.map { e =>
        val (base64, hash, uri) = e
        <publish uri={uri.toString} hash={hash.hash}>
          {base64.value}
        </publish>
      }
    )
  }.mkString
  
  private def snapshotXml(sessionId: String, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <snapshot xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId} serial={serial.toString()}>
      {pdus}
    </snapshot>

}

/**
 * Holds the global snapshot state
 */
object SnapshotState extends SnapshotStateService {

  override val db: DBType = DB.onFS
}

trait SnapshotStateService extends Urls with Logging with Hashing {

  val sessionId = conf.currentSessionId

  val repositoryWriter = wire[RepositoryWriter]

  val notificationState = wire[NotificationStateUpdater]

  val db : DB.DBType

  lazy val objectStore = new ObjectStore(db)

  lazy val serverStateStore = new ServerStateStore(db)

  lazy val deltaStore = new DeltaStore(db)

  private var changeSet = emptyChangeSet

  def emptyChangeSet = new ChangeSet(Map.empty)

  def get = changeSet

  def initializeWith(initState: ChangeSet) = {
    changeSet = initState
  }

  def list(clientId: ClientId) = objectStore.list(clientId).map { pdu =>
    val (base64, hash, uri) = pdu
    ListR(uri, hash.hash, None)
  }

  def updateWith(clientId: ClientId, queries: Seq[QueryPdu]): Seq[ReplyPdu] = synchronized {
    val oldServerState = serverStateStore.get
    val newServerState = oldServerState.next
    persistForClient(clientId, queries, newServerState) { replies : Seq[ReplyPdu] =>
      serverStateStore.update(newServerState)
      val pdus = objectStore.listAll
      val snapshotXml = Snapshot(newServerState, pdus).serialize
      val deltas = changeSet.next(newServerState, queries) // TODO handle this in the db
      val newNotification = Notification.create(snapshotXml, newServerState, deltas)
      repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, deltas, newNotification, snapshotXml) match {
        case Success(_) =>
          notificationState.update(newNotification)
          Seq()
        case Failure(e) =>
          logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
          Seq(ReportError(BaseError.CouldNotPersist, Some("Could not write XML files to filesystem: " + e.getMessage)))
      }
    }
  }

  /*
   * TODO Check if the client doesn't try to modify objects that belong to other client
   */
  def persistForClient(clientId: ClientId, queries: Seq[QueryPdu], serverState: ServerState)(f : Seq[ReplyPdu] => Seq[ReplyPdu]) = {
    val result = queries.map {
      case pq@PublishQ(uri, tag, None, base64) =>
        objectStore.find(uri) match {
          case Some((_, h, _)) =>
            Left(ReportError(BaseError.HashForInsert, Some(s"Tried to insert existing object [$uri].")))
          case None =>
            Right((PublishR(uri, tag),
              objectStore.insertAction(clientId, (base64, hash(base64), uri)),
              pq))
        }
      case pq@PublishQ(uri, tag, Some(sHash), base64) =>
        objectStore.find(uri) match {
          case Some(obj) =>
            val (_, h, _) = obj
            if (Hash(sHash) == h) {
              Right((PublishR(uri, tag),
                objectStore.updateAction(clientId, (base64, h, uri)),
                pq))
            }
            else {
              Left(ReportError(BaseError.NonMatchingHash, Some(s"Cannot republish the object [$uri], hash doesn't match")))
            }
          case None =>
            Left(ReportError(BaseError.NoObjectToUpdate, Some(s"No object [$uri] has been found.")))
        }

      case wq@WithdrawQ(uri, tag, sHash) =>
        objectStore.find(uri) match {
          case Some(obj) =>
            val (_, h, _) = obj
            if (Hash(sHash) == h)
              Right((WithdrawR(uri, tag),
                objectStore.deleteAction(clientId, h),
                wq))
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
      lazy val replies = result.collect {
        case Right((reply, _, _)) => reply
      }
      val actions = result.collect { case Right((_, action, _)) => action }
      val validPdus = result.collect { case Right((_, _, qPdu)) => qPdu }
      deltaStore.addDelta(clientId, Delta(sessionId.toString, serverState.serialNumber, validPdus))
      try {
        val transactionReplies = objectStore.atomic(actions, f(replies))
        replies ++ transactionReplies
      } catch {
        case e: Exception =>
          logger.error("Couldn't persis objects", e)
          Seq(ReportError(BaseError.CouldNotPersist, Some(s"A problem occurred while persisting the changes: " + e)))
      }
    }
  }

}


