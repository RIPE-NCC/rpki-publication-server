package net.ripe.rpki.publicationserver.store

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.ClientId

object ObjectStore {
  type State = Map[URI, (Bytes, Hash, ClientId)]
}


