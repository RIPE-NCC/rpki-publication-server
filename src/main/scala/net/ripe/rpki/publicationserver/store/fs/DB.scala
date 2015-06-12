package net.ripe.rpki.publicationserver.store.fs

import net.ripe.rpki.publicationserver.{Base64, Hash}

case class ClientId(value: String)


class DB {
  type RRDPObject = (Base64, Hash)

  def list(cliendId: ClientId): Seq[RRDPObject] = Seq()

  def publish(cliendId: ClientId, obj: RRDPObject): Unit = {}

}
