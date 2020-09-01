package net.ripe.rpki.publicationserver.model

import net.ripe.rpki.publicationserver.repository.HashingSizedStream

object IOStream {
  def string(s: String, stream: HashingSizedStream): Unit =
    stream.write(s.getBytes("US-ASCII"))
}
