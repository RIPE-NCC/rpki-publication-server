package net.ripe.rpki.publicationserver

import java.io.InputStream

import com.google.common.io.BaseEncoding

object Binaries {
  val base64: BaseEncoding = BaseEncoding.base64()

  case class Base64(value: String)

  case class Bytes(value: Array[Byte]) {
    override def equals(obj: Any): Boolean = {
      if (canEqual(obj)) {
        obj.asInstanceOf[Bytes].value.sameElements(this.value)
      } else
        false
    }
  }

  object Bytes {
    def fromStream(is: InputStream): Bytes = {
      val bytes = Stream.continually(is.read)
        .takeWhile(_ != -1)
        .map(_.toByte)
        .toArray
      Bytes(bytes)
    }

    def fromBase64(b64: Base64): Bytes = Bytes(base64.decode(b64.value))

    def toBase64(bytes: Bytes): Base64 = Base64(base64.encode(bytes.value))
  }

}
