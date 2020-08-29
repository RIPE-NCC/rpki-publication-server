package net.ripe.rpki.publicationserver

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes

package object model {

  case class ClientId(value: String)

  type RRDPObject = (Bytes, Hash, URI, ClientId)

  case class DbVersion(value: Long)
}
