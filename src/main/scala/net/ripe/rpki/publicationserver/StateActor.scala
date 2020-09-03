package net.ripe.rpki.publicationserver

import java.net.URI

import akka.actor.{Actor, OneForOneStrategy, Props, Status, SupervisorStrategy}
import net.ripe.rpki.publicationserver.messaging.Accumulator
import net.ripe.rpki.publicationserver.messaging.Messages.{InitRepo, RawMessage, ValidatedStateMessage}
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.postresql.{PgStore, RollbackException}

object StateActor {
  def props(conf: AppConfig, metrics: Metrics): Props = Props(new StateActor(conf, metrics))
}

class StateActor(conf: AppConfig, metrics: Metrics)
    extends Actor with Hashing with Logging {

  lazy val objectStore = PgStore.get(conf.pgConfig)

  var state: ObjectStore.State = _

  val accActor = context.actorOf(Accumulator.props(conf), "accumulator")

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1) {
    case _: Exception =>
      SupervisorStrategy.Escalate
  }

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

    val replyStatus = try {
      implicit val m = metrics
      objectStore.applyChanges(queryMessage, clientId)
      convertToReply(queryMessage)
    } catch {
      case e: RollbackException => ErrorMsg(e.error)
      case e: Exception => ErrorMsg(BaseError("other_error", s"Unknown error: ${e.getMessage}"))
    }

    sender() ! replyStatus

    replyStatus match {
      case _: ReplyMsg =>
        accActor ! ValidatedStateMessage(queryMessage, state)
      case e: ErrorMsg =>
        logger.warn(s"Error processing query from $clientId: ${e.error.message}")
    }
  }

  def processListMessage(clientId: ClientId, tag: Option[String]): Unit = {
    try {
      val replies = objectStore.list(clientId).collect {
        case (url, hash) =>
          ListR(URI.create(url), hash, tag)
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
        case WithdrawQ(uri, tag, _)   => WithdrawR(uri, tag)
      })
  }
}
