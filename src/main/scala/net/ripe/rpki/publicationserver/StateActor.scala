package net.ripe.rpki.publicationserver

import akka.actor.{Actor, ActorRef, Props}
import net.ripe.rpki.publicationserver.messaging.Accumulator
import net.ripe.rpki.publicationserver.messaging.Messages.{RawMessage, ValidatedMessage}
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.ObjectStore.State

object StateActor {
  def props: Props = Props(new StateActor)
}


class StateActor extends Actor with Hashing with Logging {

  lazy val objectStore = new ObjectStore

  var state: ObjectStore.State = _

  val accActor: ActorRef = context.actorOf(Accumulator.props, "accumulator")

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    state = objectStore.getState
  }


  override def receive: Receive = {
    case RawMessage(queryMessage@QueryMessage(_), clientId) =>
      processQueryMessage(queryMessage, clientId)
    case RawMessage(ListMessage(), clientId) =>
      processListMessage(clientId)
  }

  private def processQueryMessage(queryMessage: QueryMessage, clientId: ClientId): Unit = {
    try {
      applyMessages(queryMessage, clientId) match {
        case Right(s) =>
          objectStore.applyChanges(queryMessage, clientId)
          state = s
          sender() ! akka.actor.Status.Success("OK")
        case Left(err) =>
          sender() ! akka.actor.Status.Success(err)
      }
    } catch {
      case e: Exception =>
        sender() ! akka.actor.Status.Failure(e)
        throw e;
    }
    accActor ! ValidatedMessage(queryMessage, state)
  }

  def applyMessages(queryMessage: QueryMessage, clientId: ClientId) = {
    queryMessage.pdus.foldLeft(Right(state): Either[ReportError, State]) { (stateOrError, pdu) =>
      stateOrError match {
        case Right(s) => applyPdu(s, pdu, clientId)
        case e => e
      }
    }
  }

  def applyPdu(state: State, pdu: QueryPdu, clientId: ClientId): Either[ReportError, State] =
    pdu match {

      case PublishQ(uri, tag, None, base64) =>
        if (state.contains(uri)) {
          Left(ReportError(BaseError.HashForInsert, Some(s"Tried to insert existing object [$uri].")))
        } else {
          Right(state + (uri ->(base64, hash(base64), clientId)))
        }

      case PublishQ(uri, tag, Some(strHash), base64) =>
        state.get(uri) match {
          case Some((_, Hash(h), _)) =>
            if (h == strHash)
              Right(state + (uri ->(base64, hash(base64), clientId)))
            else
              Left(ReportError(BaseError.NonMatchingHash, Some(s"Cannot republish the object [$uri], hash doesn't match")))
          case None =>
            Left(ReportError(BaseError.NoObjectToUpdate, Some(s"No object [$uri] has been found.")))
        }

      case WithdrawQ(uri, tag, strHash) =>
        state.get(uri) match {
          case Some((base64, Hash(h), _)) =>
            if (h == strHash)
              Right(state - uri)
            else {
              Left(ReportError(BaseError.NonMatchingHash, Some(s"Cannot withdraw the object [$uri], hash doesn't match.")))
            }
          case None =>
            Left(ReportError(BaseError.NoObjectForWithdraw, Some(s"No object [$uri] found.")))
        }
    }

  def processListMessage(clientId: ClientId): Unit = {

  }
}
