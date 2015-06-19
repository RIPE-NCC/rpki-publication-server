package net.ripe.rpki.publicationserver

import java.util.UUID

import net.ripe.rpki.publicationserver.model.{Notification, SnapshotLocator}

import scala.annotation.tailrec
import scala.io.Source

object NotificationParser extends MessageParser[Notification] {

  override val Schema = Source.fromURL(getClass.getResource("/rrdp-schema.rng")).mkString

  override protected def parse(parser: StaxParser): Either[BaseError, Notification] = {

    def captureNotificationParameters(attrs: Map[String, String]): (UUID, BigInt) = {
      assert(attrs("version") == "1", "The version attribute in the notification root element MUST be 1")
      val sessionId = UUID.fromString(attrs("session_id"))
      val serial = BigInt(attrs("serial"))
      assert(serial > 0, "The serial attribute must be an unbounded, unsigned positive integer")
      (sessionId, serial)
    }

    @tailrec
    def parseNext(sessionId: UUID, serial: BigInt, snapshotLocator: SnapshotLocator): Either[BaseError, Notification] = {

      assert(parser.hasNext, s"The notification.xml file does not contain a complete 'notification' element")

      parser.next match {
        case ElementStart(label, attrs) =>

          label.toLowerCase match {
            case "notification" =>
              val p = captureNotificationParameters(attrs)
              parseNext(p._1, p._2, snapshotLocator)
            case "snapshot" =>
              parseNext(sessionId, serial, SnapshotLocator(uri=attrs("uri"), hash=Hash(attrs("hash"))))
            case "delta" =>
              parseNext(sessionId, serial, snapshotLocator)
          }

        case ElementEnd(label) =>
          if ("notification" == label.toLowerCase) Right(Notification(sessionId, serial, snapshotLocator, Seq()))
          else parseNext(sessionId, serial, snapshotLocator)

        case _ =>
          parseNext(sessionId, serial, snapshotLocator)
      }
    }

    parseNext(null, null, null)
  }
}

