package net.ripe.rpki.publicationserver

import java.io.{InputStream, OutputStream}
import java.util.{Arrays, Base64 => B64}
import com.google.common.io.ByteStreams

object Binaries {

  private val base64decoder = B64.getDecoder
  private val base64encoder = B64.getEncoder

  case class Base64(value: String)

  case class Bytes(value: Array[Byte]) {
    override def equals(obj: Any): Boolean = obj match {
      case that: Bytes => that.canEqual(this) && Arrays.equals(this.value, that.value)
      case _ => false
    }

    override def hashCode(): Int = Arrays.hashCode(value)

    override def toString: String = s"${this.productPrefix}(${Hashing.bytesToHex(value)})"
  }

  object Bytes {
    def fromStream(is: InputStream): Bytes = Bytes(ByteStreams.toByteArray(is))

    def fromBase64(b64: Base64): Bytes = Bytes(base64decoder.decode(b64.value))

    def toBase64(bytes: Bytes): Base64 = Base64(base64encoder.encodeToString(bytes.value))
  }

}
