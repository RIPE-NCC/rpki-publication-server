package net.ripe.rpki.publicationserver.model

import java.util.UUID

import net.ripe.rpki.publicationserver.{WithdrawQ, PublishQ, Hashing, QueryPdu}

import scala.xml.{Elem, Node}

case class Delta(sessionId: UUID, serial: Long, pdus: Seq[QueryPdu]) extends Hashing {

  lazy val serialized = serialize.mkString
  lazy val contentHash = hash(serialized.getBytes)

  def serialize = deltaXml(
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
    <delta xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId} serial={serial.toString()}>
      {pdus}
    </delta>

}