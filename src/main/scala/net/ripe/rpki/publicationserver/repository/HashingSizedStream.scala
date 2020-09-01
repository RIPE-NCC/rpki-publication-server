package net.ripe.rpki.publicationserver.repository;

import java.io.OutputStream
import java.security.MessageDigest

import net.ripe.rpki.publicationserver.{Hash, Hashing};

class HashingSizedStream(val os: OutputStream) extends Hashing {
  private var size: Long = 0
  private val digest = MessageDigest.getInstance("SHA-256")

  def write(bytes: Array[Byte]): Unit = {
    digest.update(bytes)
    size += bytes.length
    os.write(bytes)
  }

  def info = (Hash(bytesToHex(digest.digest())), size)

  def close() = os.close()
}
