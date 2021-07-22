package net.ripe.rpki.publicationserver.repository

import java.io.{FilterOutputStream, OutputStream}
import java.security.MessageDigest
import net.ripe.rpki.publicationserver.Hash

class HashingSizedStream(os: OutputStream) extends FilterOutputStream(os) {
  private var size: Long = 0
  private val digest = MessageDigest.getInstance("SHA-256")

  override def write(b: Int): Unit = {
    out.write(b)
    digest.update(b.asInstanceOf[Byte])
    size += 1
  }

  override def write(bytes: Array[Byte], offset: Int, len: Int): Unit = {
    out.write(bytes, offset, len)
    digest.update(bytes, offset, len)
    size += len - offset
  }

  def summary = (Hash(digest.digest()), size)
}
