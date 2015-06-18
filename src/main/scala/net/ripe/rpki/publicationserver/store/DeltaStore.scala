package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

class DeltaStore(db: DB.DBType) extends Hashing {

  import DB._
  import slick.driver.H2Driver.api._

  private var deltaMap = Map[Long, Delta]()

  def addDelta(clientId: ClientId, delta: Delta) = {
    val ClientId(cId) = clientId
    val actions = delta.pdus.map {
      case PublishQ(u, tag, Some(h), b64) =>
        deltas += ((u.toString, h, b64.value, cId, delta.serial, 'P'))
      case PublishQ(u, tag, None, b64) =>
        deltas += ((u.toString, null, b64.value, cId, delta.serial, 'P'))
      case WithdrawQ(u, tag, h) =>
        deltas += ((u.toString, h, "", cId, delta.serial, 'W'))
    }
    DBIO.seq(DBIO.seq(actions:_*), liftDB {
      deltaMap = deltaMap + (delta.serial -> delta)
    })
  }

  def getDeltas = deltaMap.values.toSeq.sortBy(_.serial)

  def initCache(sessionId: UUID) = {
    val changes = Await.result(db.run(deltas.result), Duration.Inf)
    deltaMap = changes.groupBy(_._5).map { p =>
      val pdus: Seq[QueryPdu] = p._2.map {
        case (uri, hash, b64, _, serial, 'P') =>
          PublishQ(new URI(uri), None, Option(hash), Base64(b64))
        case (uri, hash, _, _, serial, 'W') =>
          WithdrawQ(new URI(uri), None, hash)
      }
      (p._1, Delta(sessionId.toString, p._1, pdus))
    }
  }

}
