package net.ripe.rpki.publicationserver.store

import java.net.URI
import java.util.{Date, UUID}

import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.{ClientId, Delta}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class UpdateStore extends Hashing with Logging {

  import DB._
  import slick.driver.DerbyDriver.api._

  import scala.collection.JavaConversions._

  lazy val conf = wire[AppConfig]

  private val deltaMap: scala.collection.concurrent.Map[Long, Delta] = new java.util.concurrent.ConcurrentHashMap[Long, Delta]()

  def updateAction(clientId: ClientId, delta: Delta) = {
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
      }).transactionally
    }
  }

  def getDeltas = deltaMap.values.toSeq.sortBy(_.serial)

  def getDelta(serial: Long) = deltaMap.get(serial)

  def initCache(sessionId: UUID) = {
    val changes = Await.result(db.run(deltas.result), conf.defaultTimeout)
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
    if (deltaMap.isEmpty) {
      Iterable()
    } else {
      val deltasNewestFirst: Seq[Delta] = deltaMap.values.toSeq.sortBy(-_.serial)
      var accDeltaSize = deltasNewestFirst.head.binarySize
      val timeToRemove = afterRetainPeriod(retainPeriod)
      deltasNewestFirst.tail.find { d =>
        accDeltaSize += d.binarySize
        accDeltaSize > snapshotSize
      } foreach { firstDeltaToRemove =>
        logger.info(s"Deltas older than ${firstDeltaToRemove.serial} will be removed after $timeToRemove, " +
          s"the total size of remaining deltas is ${accDeltaSize - firstDeltaToRemove.binarySize}")
        deltaMap.foreach { x =>
          val (serial, delta) = x
          if (serial <= firstDeltaToRemove.serial && delta.whenToDelete.isEmpty) {
            deltaMap.replace(serial, delta.markForDeletion(timeToRemove))
          }
        }
      }
      deltaMap.values
    }
  }

  def afterRetainPeriod(period: Duration): Date = new Date(System.currentTimeMillis() + period.toMillis)

  def clear() = {
    Await.result(db.run(deltas.delete), conf.defaultTimeout)
    deltaMap.clear()
  }

  def delete(toDelete: Iterable[Delta]) = {
    val action = deltas.filter(d => d.serial inSet toDelete.map(_.serial)).delete.transactionally
    Await.result(db.run(action), conf.defaultTimeout)
    deltaMap --= toDelete.map(_.serial)
    logger.info(s"Deleted ${toDelete.size} deltas from store and memory")
  }
}

object UpdateStore {
  lazy val store = new UpdateStore

  def get = store
}
