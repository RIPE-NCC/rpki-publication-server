package net.ripe.rpki.publicationserver.model

import java.io.ByteArrayOutputStream
import java.util.{Date, UUID}

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.{Formatting, Hash, Hashing, PublishQ, QueryPdu, WithdrawQ}

case class Delta(sessionId: UUID, serial: Long, pdus: Seq[QueryPdu], whenToDelete: Option[Date] = None) extends Hashing with Formatting {

  lazy val bytes: Array[Byte] = serialize
  lazy val contentHash: Hash = hash(bytes)
  lazy val binarySize: Int = bytes.length

  def markForDeletion(d: Date): Delta = copy(whenToDelete = Some(d))

  private def serialize: Array[Byte] = {
    val stream = new ByteArrayOutputStream()
//    IOStream.string(s"""<delta version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
//    pdus.foreach {
//      case PublishQ(uri, _, None, bytes) =>
//        IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}">""", stream)
//        IOStream.string(Bytes.toBase64(bytes).value, stream)
//        IOStream.string("</publish>\n", stream)
//      case PublishQ(uri, _, Some(hash), bytes) =>
//        IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}" hash="$hash">""", stream)
//        IOStream.string(Bytes.toBase64(bytes).value, stream)
//        IOStream.string("</publish>\n", stream)
//      case WithdrawQ(uri, _, hash) =>
//        IOStream.string(s"""<withdraw uri="${attr(uri.toASCIIString)}" hash="$hash"/>\n""", stream)
//    }
//    IOStream.string("</delta>", stream)
    stream.toByteArray
  }
}
