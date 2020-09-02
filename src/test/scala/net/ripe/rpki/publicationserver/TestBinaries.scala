package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.Binaries.Bytes

object TestBinaries {

  def generateSomeBase64() = {
    val randomBytes = Array.fill(20)((scala.util.Random.nextInt(256) - 128).toByte)
    Bytes.toBase64(Bytes(randomBytes)).value
  }
}
