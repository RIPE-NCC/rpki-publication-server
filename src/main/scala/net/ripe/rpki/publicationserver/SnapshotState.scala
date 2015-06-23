package net.ripe.rpki.publicationserver

import java.util.UUID

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.fs.RepositoryWriter
import net.ripe.rpki.publicationserver.store.{DB, DeltaStore, ObjectStore, ServerStateStore}
import slick.dbio.DBIO

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
import slick.driver.H2Driver.api._
import scala.concurrent.ExecutionContext.Implicits.global



/**
 * Holds the global snapshot state
 */
object SnapshotState extends SnapshotStateService {

}

trait SnapshotStateService extends Urls with Logging with Hashing {

  val sessionId = conf.currentSessionId

  val repositoryWriter = wire[RepositoryWriter]

  val notificationState = wire[NotificationState]

  val db = DB.db

  lazy val objectStore = new ObjectStore

  lazy val serverStateStore = new ServerStateStore

  lazy val deltaStore = new DeltaStore

  def init(sessionId: UUID) = {
    logger.info("Initializing delta cache")
    deltaStore.initCache(sessionId)

    val serverState = serverStateStore.get
    val snapshot = Snapshot(serverState, objectStore.listAll)
    logger.info("Writing snapshot")
    repositoryWriter.writeSnapshot(conf.locationRepositoryPath, serverState, snapshot)

    logger.info("Writing delta's")
    deltaStore.getDeltas.foreach(repositoryWriter.writeDelta(conf.locationRepositoryPath, _))
  }

  def list(clientId: ClientId) = objectStore.list(clientId).map { pdu =>
    val (_, hash, uri) = pdu
    ListR(uri, hash.hash, None)
  }

  def updateWith(clientId: ClientId, queries: Seq[QueryPdu]): Seq[ReplyPdu] = synchronized {
    val oldServerState = serverStateStore.get
    val newServerState = oldServerState.next
    val results = getPersistAction(clientId, queries, newServerState)

    val errors = results.collect { case Left(error) => error }
    if (errors.nonEmpty) {
      errors
    }
    else {
      val replies = results.collect { case Right((reply, _, _)) => reply }
      val actions = results.collect { case Right((_, action, _)) => action }
      val validPdus = results.collect { case Right((_, _, qPdu)) => qPdu }

      try {
        val deltaAction = deltaStore.addDeltaAction(clientId, Delta(sessionId, newServerState.serialNumber, validPdus))

        val action = (for {
          _           <- DBIO.seq(actions: _*)
          _           <- DBIO.seq(deltaAction)
          _           <- serverStateStore.updateAction(newServerState)
          objActions  <- objectStore.getAllAction
          x <- {
            // TODO Saving to the XML files should be asynchronous
            val deltas = deltaStore.getDeltas
            val snapshot = Snapshot(newServerState, objActions)
            val newNotification = Notification.create(snapshot, newServerState, deltas)
            repositoryWriter.writeNewState(conf.locationRepositoryPath, newServerState, deltas, newNotification, snapshot) match {
              case Success(_) =>
                notificationState.update(newNotification)
                DBIO.successful(replies)
              case Failure(e) =>
                logger.error("Could not write XML files to filesystem: " + e.getMessage, e)
                DBIO.failed(e)
            }
          }
        } yield x
          ).transactionally

        Await.result(db.run(action), Duration.Inf)
        replies
      } catch {
        case e: Exception =>
          logger.error("Couldn't persist objects", e)
          Seq(ReportError(BaseError.CouldNotPersist, Some(s"A problem occurred while persisting the changes: " + e)))
      }
    }
  }

  /*
   * TODO Check if the client doesn't try to modify objects that belong to other client
   */
  def getPersistAction(clientId: ClientId, queries: Seq[QueryPdu], serverState: ServerState) = {
    queries.map {
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
  }

}

