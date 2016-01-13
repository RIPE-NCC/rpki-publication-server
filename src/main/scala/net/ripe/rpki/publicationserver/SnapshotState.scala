package net.ripe.rpki.publicationserver

import java.util.UUID

import akka.actor.{Actor, ActorRef}
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.store.fs.{InitCommand, WriteCommand}
import net.ripe.rpki.publicationserver.store.{DB, DeltaStore, ObjectStore, ServerStateStore}
import slick.dbio.DBIO
import slick.driver.DerbyDriver.api._

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Await}


/**
 * Holds the global snapshot state
 */
object SnapshotState extends SnapshotStateService {
  val semaphore = new Object()
}

trait SnapshotStateService extends Config with Logging with Hashing {

  import scala.concurrent.ExecutionContext.Implicits.global

  import SnapshotState.semaphore

  val db = DB.db

  lazy val objectStore = new ObjectStore

  lazy val serverStateStore = new ServerStateStore

  lazy val deltaStore = DeltaStore.get

  var sessionId: UUID = _

  var fsWriter: ActorRef = _

  def init(fsWriterActor: ActorRef) = {
    fsWriter = fsWriterActor

    sessionId = serverStateStore.get.sessionId

    logger.info("Initializing delta cache")
    deltaStore.initCache(sessionId)

    val serverState = serverStateStore.get
    fsWriter ! InitCommand(serverState)
  }

  def list(clientId: ClientId) = semaphore.synchronized {
    objectStore.list(clientId).map { pdu =>
      val (_, hash, uri) = pdu
      ListR(uri, hash.hash, None)
    }
  }

  def updateWith(clientId: ClientId, queries: Seq[QueryPdu]): Seq[ReplyPdu] = semaphore.synchronized {
    val oldServerState = serverStateStore.get
    val newServerState = oldServerState.next
    val results = getPersistAction(clientId, queries, newServerState)

    val errors = results.collect { case Left(error) => error }
    if (errors.nonEmpty) {
      errors
    }
    else {
      val replies = Future(results.collect { case Right((reply, _, _)) => reply })
      val actions = Future(results.collect { case Right((_, action, _)) => action })
      val validPdus = results.collect { case Right((_, _, qPdu)) => qPdu }

      try {
        val deltaAction = deltaStore.addDeltaAction(clientId, Delta(sessionId, newServerState.serialNumber, validPdus))
        val serverStateAction = serverStateStore.updateAction(newServerState)

        val publishActions = DBIO.seq(waitFor(actions): _*)
        val allActions = DBIO.seq(publishActions, deltaAction, serverStateAction).transactionally
        Await.result(db.run(allActions), conf.defaultTimeout)

        fsWriter ! WriteCommand(newServerState)
        waitFor(replies)
      } catch {
        case e: Exception =>
          logger.error("Couldn't persist objects", e)
          Seq(ReportError(BaseError.CouldNotPersist, Some(s"A problem occurred while persisting the changes: " + e)))
      }
    }
  }

  def waitFor[T](f: Future[T]) = Await.result(f, Duration.Inf)

  def snapshotRetainPeriod = conf.unpublishedFileRetainPeriod

  /*
   * TODO Check if the client doesn't try to modify objects that belong to other client
   */
  def getPersistAction(clientId: ClientId, queries: Seq[QueryPdu], serverState: ServerState) = {
    queries.par.map {
      case publishQ@PublishQ(uri, tag, None, base64) =>
        objectStore.find(uri) match {
          case Some((_, h, _)) =>
            Left(ReportError(BaseError.HashForInsert, Some(s"Tried to insert existing object [$uri].")))
          case None =>
            Right((PublishR(uri, tag),
              objectStore.insertAction(clientId, (base64, hash(base64), uri)),
              publishQ))
        }
      case publishQ@PublishQ(uri, tag, Some(sHash), base64) =>
        objectStore.find(uri) match {
          case Some(obj) =>
            val (_, h, _) = obj
            if (Hash(sHash) == h) {
              Right((PublishR(uri, tag),
                objectStore.updateAction(clientId, (base64, hash(base64), uri)),
                publishQ))
            }
            else {
              Left(ReportError(BaseError.NonMatchingHash, Some(s"Cannot republish the object [$uri], hash doesn't match")))
            }
          case None =>
            Left(ReportError(BaseError.NoObjectToUpdate, Some(s"No object [$uri] has been found.")))
        }

      case withdrawQ@WithdrawQ(uri, tag, sHash) =>
        objectStore.find(uri) match {
          case Some(obj) =>
            val (_, h, _) = obj
            if (Hash(sHash) == h)
              Right((WithdrawR(uri, tag),
                objectStore.deleteAction(clientId, h),
                withdrawQ))
            else {
              Left(ReportError(BaseError.NonMatchingHash, Some(s"Cannot withdraw the object [$uri], hash doesn't match.")))
            }
          case None =>
            Left(ReportError(BaseError.NoObjectForWithdraw, Some(s"No object [$uri] found.")))
        }
    }.seq
  }

}

