package net.ripe.rpki.publicationserver

import java.security.MessageDigest

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}

case class Hash(hash: String)

trait Hashing {

  def stringify(bytes: Array[Byte]): String = Option(bytes).map(bytesToHex).getOrElse("")

  private val HEX_ARRAY = "0123456789ABCDEF".toCharArray

  // Copy-pasted from here
  // https://stackoverflow.com/questions/9655181/how-to-convert-a-byte-array-to-a-hex-string-in-java
  // as the fastest way to do it
  def bytesToHex(bytes: Array[Byte]): String = {
    val hexChars = new Array[Char](bytes.length * 2)
    var j = 0
    while (j < bytes.length) {
      val v = bytes(j) & 0xFF
      hexChars(j * 2) = HEX_ARRAY(v >>> 4)
      hexChars(j * 2 + 1) = HEX_ARRAY(v & 0x0F)
      j += 1
    }
    new String(hexChars)
  }

  def hash(bytes: Array[Byte]): Hash = {
    val sha256 = MessageDigest.getInstance("SHA-256")
    Hash(stringify(sha256.digest(bytes)))
  }

  def hash(b64: Base64): Hash = hash(Bytes.fromBase64(b64))
  def hash(bytes: Bytes): Hash = hash(bytes.value)
}
