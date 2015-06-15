package net.ripe.rpki.publicationserver.store.fs

import java.net.URI

import net.ripe.rpki.publicationserver.{Hash, Base64}

case class ClientId(value: String)

trait DB {
  type RRDPObject = (Base64, Hash, URI)

  def list(cliendId: ClientId): Seq[RRDPObject]

  def publish(cliendId: ClientId, obj: RRDPObject): Unit

}
