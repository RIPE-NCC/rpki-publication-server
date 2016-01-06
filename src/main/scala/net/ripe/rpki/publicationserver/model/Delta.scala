package net.ripe.rpki.publicationserver.model

import java.util.{Date, UUID}

import net.ripe.rpki.publicationserver.{WithdrawQ, PublishQ, Hashing, QueryPdu}

import scala.xml.{Elem, Node}

case class Delta(sessionId: UUID, serial: Long, pdus: Seq[QueryPdu], whenToDelete : Option[Date] = None) extends Hashing {

  lazy val bytes = serialize.mkString.getBytes("UTF-8")
  lazy val contentHash = hash(bytes)
  lazy val binarySize = bytes.length

  def markForDeletion(d: Date) = copy(whenToDelete = Some(d))

  private def serialize = deltaXml(
    sessionId.toString,
    serial,
    pdus.map {
      case PublishQ(uri, _, None, base64) => <publish uri={uri.toString}>
        {base64.value}
      </publish>
      case PublishQ(uri, _, Some(hash), base64) => <publish uri={uri.toString} hash={hash}>
        {base64.value}
      </publish>
      case WithdrawQ(uri, _, hash) => <withdraw uri={uri.toString} hash={hash}/>
    }
  )

  private def deltaXml(sessionId: String, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <delta xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId} serial={serial.toString()}>
      {pdus}
    </delta>

}
