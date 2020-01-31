package net.ripe.rpki.publicationserver

import java.net.URI

import akka.actor.{Actor, Props, Status}
import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.messaging.Accumulator
import net.ripe.rpki.publicationserver.messaging.Messages.{InitRepo, RawMessage, ValidatedMessage}
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.ObjectStore.State

object StateActor {
  def props(conf: AppConfig): Props = Props(new StateActor(conf))
}


class StateActor(conf: AppConfig) extends Actor with Hashing with Logging {

  lazy val objectStore = ObjectStore.get

  var state: ObjectStore.State = _

  val accActor = context.actorOf(Accumulator.props(conf), "accumulator")

  @throws[Exception](classOf[Exception])
  override def preStart() = {
    state = objectStore.getState
    accActor ! InitRepo(state)
  }


  override def receive: Receive = {
    case RawMessage(queryMessage@QueryMessage(_), clientId) =>
      processQueryMessage(queryMessage, clientId)
    case RawMessage(ListMessage(), clientId) =>
      processListMessage(clientId, None) // TODO ??? implement tags for list query
  }

  private def processQueryMessage(queryMessage: QueryMessage, clientId: ClientId): Unit = {
    def try_[T](f: => T) = try f catch {
      case e: Exception =>
        logger.error("Error processing query", e)
        Status.Failure(e)
    }

    val replyStatus = try_ {
      applyMessages(queryMessage, clientId) match {
        case Right(s) =>
          try_ {
            objectStore.applyChanges(queryMessage, clientId)
            state = s
            convertToReply(queryMessage)
          }
        case Left(err) =>
          ErrorMsg(BaseError(err.code, err.message.getOrElse("Unspecified error")))
      }
    }

    sender() ! replyStatus

    replyStatus match {
      case _: ReplyMsg =>
        accActor ! ValidatedMessage(queryMessage, state)
      case e: ErrorMsg =>
        logger.warn(s"Error processing query from $clientId: ${e.error.message}")
    }
  }

  private def applyMessages(queryMessage: QueryMessage, clientId: ClientId): Either[ReportError, State] = {
    queryMessage.pdus.foldLeft(Right(state): Either[ReportError, State]) { (stateOrError, pdu) =>
      stateOrError.right.flatMap(state => applyPdu(state, pdu, clientId))
    }
  }

  private def applyPdu(state: State, pdu: QueryPdu, clientId: ClientId): Either[ReportError, State] = {
    pdu match {
      case PublishQ(uri, tag, None, bytes) =>
        applyCreate(state, clientId, uri, bytes)
      case PublishQ(uri, tag, Some(strHash), bytes) =>
        applyReplace(state, clientId, uri, strHash, bytes)
      case WithdrawQ(uri, tag, strHash) =>
        applyDelete(state, uri, strHash)
    }
  }

  private def applyDelete(state: State, uri: URI, hashToReplace: String): Either[ReportError, State] = {
    state.get(uri) match {
      case Some((_, Hash(foundHash), _)) =>
        if (foundHash.toUpperCase == hashToReplace.toUpperCase) {
          logger.debug(s"Deleting $uri with hash ${foundHash.toUpperCase}")
          Right(state - uri)
        } else {
          Left(ReportError(BaseError.NonMatchingHash,
            Some(s"Cannot withdraw the object [$uri], hash doesn't match, passed ${hashToReplace.toUpperCase}, but existing one is ${foundHash.toUpperCase}.")))
        }
      case None =>
        Left(ReportError(BaseError.NoObjectForWithdraw, Some(s"No object [$uri] found.")))
    }
  }

  private def applyReplace(state: State, clientId: ClientId, uri: URI, hashToReplace: String, bytes: Bytes): Either[ReportError, State] = {
    state.get(uri) match {
      case Some((_, Hash(foundHash), _)) =>
        if (foundHash.toUpperCase == hashToReplace.toUpperCase)
          Right(state + (uri -> (bytes, hash(bytes), clientId)))
        else
        if (foundHash.toUpperCase == hashToReplace.toUpperCase) {
          val newHash = hash(bytes)
          logger.debug(s"Replacing $uri with hash $foundHash -> $newHash")
          Right(state + (uri -> (bytes, newHash, clientId)))
        } else
          Left(ReportError(BaseError.NonMatchingHash,
            Some(s"Cannot republish the object [$uri], hash doesn't match, passed ${hashToReplace.toUpperCase}, but existing one is ${foundHash.toUpperCase}.")))
      case None =>
        Left(ReportError(BaseError.NoObjectToUpdate, Some(s"No object [$uri] has been found.")))
    }
  }

  def applyCreate(state: State, clientId: ClientId, uri: URI, bytes: Bytes): Either[ReportError, State] = {
    if (state.contains(uri)) {
      Left(ReportError(BaseError.HashForInsert, Some(s"Tried to insert existing object [$uri].")))
    } else {
      Right(state + (uri -> (bytes, hash(bytes), clientId)))
      val newHash = hash(bytes)
      logger.debug(s"Adding $uri with hash $newHash")
      Right(state + (uri -> (bytes, newHash, clientId)))
    }
  }

  def processListMessage(clientId: ClientId, tag: Option[String]): Unit = {
    try {
      val replies = state collect {
        case (uri, (b64, h, clId)) if clId == clientId =>
          ListR(uri, h.hash, tag)
      }
      sender() ! ReplyMsg(replies.toSeq)
    } catch {
      case e: Exception =>
        sender() ! Status.Failure(e)
    }
  }

  private def convertToReply(queryMessage: QueryMessage) = {
    ReplyMsg(
      queryMessage.pdus.map {
        case PublishQ(uri, tag, _, _) => PublishR(uri, tag)
        case WithdrawQ(uri, tag, _) => WithdrawR(uri, tag)
      })
  }
}
