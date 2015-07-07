package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.{Date, UUID}

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.{ClientId, Delta}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class DeltaStore extends Hashing with Logging {

  import DB._
  import slick.driver.H2Driver.api._
  import scala.collection.JavaConversions._

  private val deltaMap: scala.collection.concurrent.Map[Long, Delta] = new java.util.concurrent.ConcurrentHashMap[Long, Delta]()

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
        deltaMap += (delta.serial -> delta)
        logger.info(s"Added delta with serial ${delta.serial}")
        logger.info("deltaMap = "+System.identityHashCode(deltaMap))
        logger.info(deltaMap.toString())
      }).transactionally
    }
  }

  def getDeltas = deltaMap.values.toSeq.sortBy(_.serial)

  def getDelta(serial: Long) = deltaMap.get(serial)

  def initCache(sessionId: UUID) = {
    val changes = Await.result(db.run(deltas.result), Duration.Inf)
    deltaMap.clear()
    deltaMap ++= changes.groupBy(_._5).map { p =>
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
    val deltasNewestFirst: Seq[Delta] = deltaMap.values.toSeq.sortBy(-_.serial)
    var accDeltaSize = deltasNewestFirst.head.binarySize
    deltasNewestFirst.tail.find { d =>
      accDeltaSize += d.binarySize
      accDeltaSize > snapshotSize
    } foreach { firstDeltaToRemove =>
        val timeToRemove = afterRetainPeriod(retainPeriod)
        logger.info(s"Deltas older than ${firstDeltaToRemove.serial} will be scheduled for removal after $timeToRemove, the total size of remaining deltas is ${accDeltaSize - firstDeltaToRemove.binarySize}")
        deltaMap.foreach { x =>
          val(serial, delta) = x
          if (serial <= firstDeltaToRemove.serial) {
            deltaMap.replace(serial, delta.markForDeletion(timeToRemove))
          }
        }
    }
    deltaMap.values
  }

  def afterRetainPeriod(period: Duration): Date = new Date(System.currentTimeMillis() + period.toMillis)

  def clear() = {
    Await.result(db.run(deltas.delete), Duration.Inf)
    deltaMap.clear
  }

  def delete(ds: Iterable[Delta]) = {
    val q = DBIO.seq(
      DBIO.seq(ds.toSeq.map { d =>
        deltas.filter(_.serial === d.serial).delete
      }: _*),
      liftDB(deltaMap --= ds.map(_.serial))
    ).transactionally

    Await.result(db.run(q), Duration.Inf)
  }
}

object DeltaStore {
  lazy val store = new DeltaStore

  def get = store
}
