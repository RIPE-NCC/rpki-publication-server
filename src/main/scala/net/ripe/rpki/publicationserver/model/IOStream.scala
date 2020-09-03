package net.ripe.rpki.publicationserver.model

import java.nio.charset.StandardCharsets

import net.ripe.rpki.publicationserver.repository.HashingSizedStream

object IOStream {
  def string(s: String, stream: HashingSizedStream): Unit =
    stream.write(s.getBytes(StandardCharsets.US_ASCII))
}
