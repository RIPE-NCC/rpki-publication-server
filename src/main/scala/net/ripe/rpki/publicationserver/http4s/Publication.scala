package net.ripe.rpki.publicationserver.http4s

import cats.effect._
import cats.syntax.all._
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.parsing.PublicationMessageParser
import net.ripe.rpki.publicationserver.store.postresql.{PgStore, RollbackException}
import net.ripe.rpki.publicationserver.{AppConfig, Logging}
import org.http4s._
import org.http4s.dsl.io._

import java.net.URI

class Publication(conf: AppConfig, metrics: Metrics) extends Logging {

  implicit val timer : Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)

  val msgParser = new PublicationMessageParser

  lazy val objectStore = PgStore.get(conf.pgConfig)

  implicit val yearQueryParamDecoder: QueryParamDecoder[ClientId] = QueryParamDecoder[String].map(ClientId)
  object ClientIdParamMatcher extends QueryParamDecoderMatcher[ClientId]("clientId")

  def publicationService = HttpRoutes.of[IO] {
    case req @ POST -> Root / "http4s" :? ClientIdParamMatcher(clientId) =>
      processMsg(req, clientId)
  }

  def processMsg(req: Request[IO], clientId: ClientId) = {
    req.as[Array[Byte]].flatMap { xmlBytes =>
      msgParser.parse(xmlBytes) match {
        case Right(parsed) =>
          parsed match {
            case queryMessage@QueryMessage(_) =>
              processQueryMessage(queryMessage, clientId)
            case ListMessage() =>
              // TODO ??? implement tags for list query
              processListMessage(clientId, None)
          }
        case Left(msgError) =>
          logger.warn("Error while handling request: {}", msgError)
          ErrorMsg(msgError)
      }
    }
  }

  private def processQueryMessage(queryMessage: QueryMessage, clientId: ClientId) = {
    try {
      implicit val m = metrics
      objectStore.applyChanges(queryMessage, clientId)
      ReplyMsg {
        queryMessage.pdus.map {
          case PublishQ(uri, tag, _, _) => PublishR(uri, tag)
          case WithdrawQ(uri, tag, _) => WithdrawR(uri, tag)
        }
      }
    } catch {
      case e: RollbackException => ErrorMsg(e.error)
      case e: Exception => ErrorMsg(BaseError("other_error", s"Unknown error: ${e.getMessage}"))
    }
  }

  def processListMessage(clientId: ClientId, tag: Option[String]) =
    ReplyMsg {
      objectStore.list(clientId).map { case (url, hash) =>
        ListR(URI.create(url), hash, tag)
      }
    }

}
