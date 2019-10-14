package net.ripe.rpki.publicationserver.model

import java.io.ByteArrayOutputStream

object Dump {
  def streamChars(s: String, stream: ByteArrayOutputStream): Unit = {
    stream.write(s.getBytes("US-ASCII"))
  }
}
