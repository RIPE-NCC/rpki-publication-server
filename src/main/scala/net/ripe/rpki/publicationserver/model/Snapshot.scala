package net.ripe.rpki.publicationserver.model

import net.ripe.rpki.publicationserver.store.DB
import net.ripe.rpki.publicationserver.store.DB.ServerState

import scala.xml.{Elem, Node}

case class Snapshot(serverState: ServerState, pdus: Seq[DB.RRDPObject]) {

  def serialize = {
    val ServerState(sessionId, serial) = serverState
    snapshotXml(
      sessionId,
      serial,
      pdus.map { e =>
        val (base64, hash, uri) = e
        <publish uri={uri.toString} hash={hash.hash}>
          {base64.value}
        </publish>
      }
    )
  }.mkString

  private def snapshotXml(sessionId: String, serial: BigInt, pdus: => Iterable[Node]): Elem =
    <snapshot xmlns="HTTP://www.ripe.net/rpki/rrdp" version="1" session_id={sessionId} serial={serial.toString()}>
      {pdus}
    </snapshot>

}

