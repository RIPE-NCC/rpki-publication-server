

package net.ripe.rpki.publicationserver.model

import net.ripe.rpki.publicationserver.Hashing
import net.ripe.rpki.publicationserver.store.DB

import scala.xml.{Elem, Node}

case class Snapshot(serverState: ServerState, pdus: Seq[DB.RRDPObject]) extends Hashing {

  lazy val serialized = serialize.mkString
  private lazy val bytes = serialized.getBytes

  lazy val contentHash = hash(bytes)
  lazy val binarySize = bytes.length

  def serialize = {
    val ServerState(sessionId, serial) = serverState
    snapshotXml(
      sessionId.toString,
      serial,
      pdus.map { e =>
        val (base64, _, uri) = e
        <publish uri={uri.toString}>
          {base64.value}
        </publish>
      }
    )
  }

  private def snapshotXml(sessionId: String, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <snapshot xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId} serial={serial.toString()}>
      {pdus}
    </snapshot>

}

