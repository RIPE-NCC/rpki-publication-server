package net.ripe.rpki.publicationserver

import java.security.MessageDigest

import com.google.common.io.BaseEncoding

case class Hash(hash: String)

trait Hashing {

  private val base64 = BaseEncoding.base64()

  def stringify(bytes: Array[Byte]) = Option(bytes).map {
    _.map { b => String.format("%02X", new Integer(b & 0xff)) }.mkString
  }.getOrElse("")

  def hash(bytes: Array[Byte]): Hash = {
    val digest = MessageDigest.getInstance("SHA-256")
    Hash(stringify(digest.digest(bytes)))
  }

  def hash(b64: Base64): Hash = {
    val Base64(b64String) = b64
    val bytes = base64.decode(b64String)
    hash(bytes)
  }
}
