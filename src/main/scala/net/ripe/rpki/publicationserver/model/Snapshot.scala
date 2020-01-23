package net.ripe.rpki.publicationserver.model

import java.io.ByteArrayOutputStream
import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.Hashing

case class Snapshot(serverState: ServerState, pdus: Seq[(Bytes, URI)]) extends Hashing {

  lazy val bytes = serialize
  lazy val contentHash = hash(bytes)
  lazy val binarySize = bytes.length

  private[model] def serialize = {
    val ServerState(sessionId, serial) = serverState
    val stream = new ByteArrayOutputStream()
    Dump.streamChars(s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    pdus.foreach { pdu =>
      val (binary, uri) = pdu
      Dump.streamChars(s"""<publish uri="$uri">""", stream)
      Dump.streamChars(Bytes.toBase64(binary).value, stream)
      Dump.streamChars("</publish>\n", stream)
    }
    Dump.streamChars("</snapshot>", stream)
    stream.toByteArray
  }

}

