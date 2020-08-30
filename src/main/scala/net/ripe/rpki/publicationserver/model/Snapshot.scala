package net.ripe.rpki.publicationserver.model

import java.io.ByteArrayOutputStream
import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.{Formatting, Hashing}

case class Snapshot(serverState: ServerState, pdus: Seq[(Bytes, URI)]) extends Hashing with Formatting {

  lazy val bytes = serialize
  lazy val contentHash = hash(bytes)
  lazy val binarySize = bytes.length

  private[model] def serialize = {
    val ServerState(sessionId, serial) = serverState
    val stream = new ByteArrayOutputStream()
    IOStream.string(s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    pdus.foreach { pdu =>
      val (bytes, uri) = pdu
      IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}">""", stream)
      IOStream.string(Bytes.toBase64(bytes).value, stream)
      IOStream.string("</publish>\n", stream)
    }
    IOStream.string("</snapshot>", stream)
    stream.toByteArray
  }

}

