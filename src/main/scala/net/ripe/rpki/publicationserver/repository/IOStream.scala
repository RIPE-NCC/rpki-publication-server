package net.ripe.rpki.publicationserver.repository

import java.io.OutputStream
import java.nio.charset.StandardCharsets

object IOStream {
  def string(s: String, stream: OutputStream): Unit =
    stream.write(s.getBytes(StandardCharsets.US_ASCII))
}
