package net.ripe.rpki.publicationserver.repository

import java.nio.charset.StandardCharsets

object IOStream {
  def string(s: String, stream: HashingSizedStream): Unit =
    stream.write(s.getBytes(StandardCharsets.US_ASCII))
}
