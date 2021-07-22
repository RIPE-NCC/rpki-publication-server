package net.ripe.rpki.publicationserver.repository

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.Hash

import java.io.{FilterOutputStream, OutputStream}
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HashingSizedStream(secret: Bytes, os: OutputStream) extends FilterOutputStream(os) {
  private var size: Long = 0
  private val digest = MessageDigest.getInstance("SHA-256")
  private val mac = Mac.getInstance("HmacSHA256")

  mac.init(new SecretKeySpec(secret.value, "HmacSHA256"))

  override def write(b: Int): Unit = {
    out.write(b)
    digest.update(b.asInstanceOf[Byte])
    mac.update(b.asInstanceOf[Byte])
    size += 1
  }

  override def write(bytes: Array[Byte], offset: Int, len: Int): Unit = {
    out.write(bytes, offset, len)
    digest.update(bytes, offset, len)
    mac.update(bytes, offset, len)
    size += len - offset
  }

  def summary = (Hash(digest.digest()), Bytes(mac.doFinal()), size)
}
