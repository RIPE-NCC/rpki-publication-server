package net.ripe.rpki.publicationserver.model

import java.io.ByteArrayOutputStream
import java.util.{Date, UUID}

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.{Hash, Hashing, PublishQ, QueryPdu, WithdrawQ}

case class Delta(sessionId: UUID, serial: Long, pdus: Seq[QueryPdu], whenToDelete: Option[Date] = None) extends Hashing {

  lazy val bytes: Array[Byte] = serialize
  lazy val contentHash: Hash = hash(bytes)
  lazy val binarySize: Int = bytes.length

  def markForDeletion(d: Date): Delta = copy(whenToDelete = Some(d))

  private def serialize: Array[Byte] = {
    val stream = new ByteArrayOutputStream()
    Dump.streamChars(s"""<delta version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    pdus.foreach {
      case PublishQ(uri, _, None, bytes) =>
        Dump.streamChars(s"""<publish uri="${uri.toASCIIString}">""", stream)
        Dump.streamChars(Bytes.toBase64(bytes).value, stream)
        Dump.streamChars("</publish>\n", stream)
      case PublishQ(uri, _, Some(hash), bytes) =>
        Dump.streamChars(s"""<publish uri="${uri.toASCIIString}" hash="$hash">""", stream)
        Dump.streamChars(Bytes.toBase64(bytes).value, stream)
        Dump.streamChars("</publish>\n", stream)
      case WithdrawQ(uri, _, hash) =>
        Dump.streamChars(s"""<withdraw uri="${uri.toASCIIString}" hash="$hash"/>\n""", stream)
    }
    Dump.streamChars("</delta>", stream)
    stream.toByteArray
  }
}
