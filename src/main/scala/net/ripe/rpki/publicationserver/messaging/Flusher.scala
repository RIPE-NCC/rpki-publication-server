package net.ripe.rpki.publicationserver.messaging


import akka.actor.{Props, Actor}

import java.net.URI
import java.time.Instant
import java.util.UUID

import akka.actor.Actor
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.fs.{RrdpRepositoryWriter, RsyncRepositoryWriter}
import net.ripe.rpki.publicationserver.{Config, Hash, Logging, QueryMessage}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

object Flusher {
  def props = Props(new Flusher)
}

class Flusher extends Actor with Config with Logging {

  import scala.concurrent.ExecutionContext.Implicits.global

  lazy val rrdpWriter = wire[RrdpRepositoryWriter]
  lazy val rsyncWriter = wire[RsyncRepositoryWriter]

  var deltas: Map[Long, (Long, Hash, Long, Instant)] = Map()

  // TODO Initialise them on the start
  val sessionId: UUID = ???

  var serial = 0L

  override def receive: Receive = {
    case BatchMessage(messages, state) =>
      flush(messages, state)
      serial += 1
  }


  def flush(messages: Seq[QueryMessage], state: ObjectStore.State) = {
    val pdus = messages.flatMap(_.pdus)
    val delta = Delta(sessionId, serial, pdus)
    deltas += serial -> (serial, delta.contentHash, delta.binarySize, Instant.now())

    val (deltasToPublish, deltasToDelete) = separateDeltas(deltas)

    val newServerState: ServerState = ???
    val snapshot: Snapshot = ???

    val newNotification = Notification.create2(snapshot, newServerState,
      deltasToPublish.map(d => (d._1, d._2)).toSeq)

    val rrdp = Future {
      logger.debug(s"Writing delta $serial to rsync filesystem")
      rrdpWriter.writeDelta(conf.rrdpRepositoryPath, delta)
    }
    val rsync = Future {
      logger.debug(s"Writing delta $serial to RRDP filesystem")
      rsyncWriter.writeDelta(delta)
    }

    waitFor(rrdp).flatMap { _ =>
      waitFor(rsync).flatMap { _ =>
        rrdpWriter.writeNewState(conf.rrdpRepositoryPath, newServerState, newNotification, snapshot)
      }.recover {
        case e: Exception =>
          logger.error(s"Could not write delta $serial to RRDP repo: ", e)
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Could not write delta $serial to rsync repo: ", e)
    } match {
      case Success(timestampOption) =>
      //            timestampOption.foreach(scheduleSnapshotCleanup
      //            val now = System.currentTimeMillis
      //            cleanupDeltas(deltasToDelete.filter(_.whenToDelete.exists(_.getTime < now)))
      case Failure(e) =>
        logger.error("Could not write notification.xml to filesystem: " + e.getMessage, e)
    }

  }

  private def waitFor[T](f: Future[T]) = Await.result(f, 10.minutes)

  def separateDeltas(deltas: Map[Long, (Long, Hash, Long, Instant)]) = {
    deltas.values.partition { d =>
      val (serial, _, size, time) = d
      // TODO Implement some cleansing criteria
      true
    }
  }

}
