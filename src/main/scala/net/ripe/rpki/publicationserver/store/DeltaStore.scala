package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.{Date, UUID}

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.{ClientId, Delta}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class DeltaStore extends Hashing {

  import DB._
  import slick.driver.H2Driver.api._

  private var deltaMap = Map[Long, Delta]()

  def addDeltaAction(clientId: ClientId, delta: Delta) = {
    val ClientId(cId) = clientId
    val actions = delta.pdus.map {
      case PublishQ(u, tag, Some(h), b64) =>
        deltas += ((u.toString, Some(h), Some(b64.value), cId, delta.serial, 'P'))
      case PublishQ(u, tag, None, b64) =>
        deltas += ((u.toString, None, Some(b64.value), cId, delta.serial, 'P'))
      case WithdrawQ(u, tag, h) =>
        deltas += ((u.toString, Some(h), None, cId, delta.serial, 'W'))
    }

    if (actions.isEmpty) {
      DBIO.successful(())
    } else {
      DBIO.seq(DBIO.seq(actions: _*), liftDB {
        deltaMap = deltaMap + (delta.serial -> delta)
      }).transactionally
    }
  }

  def getDeltas = deltaMap.values.toSeq.sortBy(_.serial)

  def getDelta(serial: Long) = deltaMap.get(serial)

  def initCache(sessionId: UUID) = {
    val changes = Await.result(db.run(deltas.result), Duration.Inf)
    deltaMap = changes.groupBy(_._5).map { p =>
      val (serial, pws) = p
      val pdus = pws.map {
        case (uri, hash, Some(b64), _, _, 'P') =>
          PublishQ(new URI(uri), None, hash, Base64(b64))
        case (uri, Some(hash), _, _, _, 'W') =>
          WithdrawQ(new URI(uri), None, hash)
      }
      (serial, Delta(sessionId, serial, pdus))
    }
  }

  def markOldestDeltasForDeletion(snapshotSize: Long, retainPeriod: Duration) = {
    var accDeltaSize = 0L
    var thresholdIndex : Option[Long] = None
    val deltas = deltaMap.toSeq.sortBy(-_._1).zipWithIndex.map { p =>
      val ((serial, delta), index) = p
      accDeltaSize += delta.binarySize
      if (accDeltaSize > snapshotSize && index > 0) {
        if (thresholdIndex.isEmpty) {
          thresholdIndex = Some(serial)
        }
        delta.markForDeletion(afterRetainPeriod(retainPeriod))
      }
      else delta
    }
    deltaMap = deltas.map(d => (d.serial, d)).toMap
    (deltas, accDeltaSize, thresholdIndex)
  }

  def afterRetainPeriod(period: Duration): Date = new Date(System.currentTimeMillis() + period.toMillis)

  def clear() = {
    Await.result(db.run(deltas.delete), Duration.Inf)
    deltaMap = Map.empty
  }

  def delete(ds: Seq[Delta]) = {
    val q = DBIO.seq(
      DBIO.seq(ds.map { d =>
        deltas.filter(_.serial === d.serial).delete
      }: _*),
      liftDB(deltaMap = deltaMap -- ds.map(_.serial))
    ).transactionally

    Await.result(db.run(q), Duration.Inf)
  }
}

object DeltaStore {
  lazy val store = new DeltaStore

  def get = store
}
