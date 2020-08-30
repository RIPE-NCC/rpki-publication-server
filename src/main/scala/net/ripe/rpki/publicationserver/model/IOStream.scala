package net.ripe.rpki.publicationserver.model

import java.io.OutputStream

object IOStream {
  def string(s: String, stream: OutputStream): Unit =
    stream.write(s.getBytes("US-ASCII"))
}
