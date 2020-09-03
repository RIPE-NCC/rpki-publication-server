package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}

object TestBinaries {

  def generateSomeBase64(size: Int = 20) = {
    val randomBytes = Array.fill(size)((scala.util.Random.nextInt(256) - 128).toByte)
    Bytes.toBase64(Bytes(randomBytes)).value
  }

  def generateObject(size: Int = 20) = {
    val randomBytes = Array.fill(size)((scala.util.Random.nextInt(256) - 128).toByte)
    val bytes = Bytes(randomBytes)
    (bytes, Bytes.toBase64(bytes).value)
  }

}
