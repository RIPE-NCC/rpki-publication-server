package net.ripe.rpki.publicationserver.repository

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.{Hashing, PublicationServerBaseTest}

class HashingSizedStreamTest extends PublicationServerBaseTest with Hashing {
  test("Hashing and size is fine") {

    val s1 = "kjbnfdskjvbdjkfbvkjbdfv"
    val s2 = ",mnsdv,mnsdvmnsdv"
    val s3 = "eloihelknfbvln"

    val baos = new ByteArrayOutputStream
    val stream = new HashingSizedStream(baos)
    IOStream.string(s1, stream)
    IOStream.string(s2, stream)
    IOStream.string(s3, stream)

    val (streamHash, size) = stream.summary

    val bytes = (s1 + s2 + s3).getBytes(StandardCharsets.US_ASCII)
    val realBytes = Bytes(bytes)
    streamHash should be(hash(realBytes))

    size should be(bytes.length)
  }
}
