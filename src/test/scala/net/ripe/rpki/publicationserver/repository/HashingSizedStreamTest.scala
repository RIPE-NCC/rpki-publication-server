package net.ripe.rpki.publicationserver.repository

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.{Hashing, PublicationServerBaseTest}

class HashingSizedStreamTest extends PublicationServerBaseTest with Hashing {
  test("Hashing and size is fine") {
    val secret = Bytes("the-secret-used-for-this-test".getBytes(StandardCharsets.US_ASCII))

    val s1 = "kjbnfdskjvbdjkfbvkjbdfv"
    val s2 = ",mnsdv,mnsdvmnsdv"
    val s3 = "eloihelknfbvln"

    val baos = new ByteArrayOutputStream
    val stream = new HashingSizedStream(secret, baos)
    IOStream.string(s1, stream)
    IOStream.string(s2, stream)
    IOStream.string(s3, stream)

    val (streamHash, streamMac, size) = stream.summary

    val bytes = (s1 + s2 + s3).getBytes(StandardCharsets.US_ASCII)
    val realBytes = Bytes(baos.toByteArray)

    baos.toByteArray should be(bytes)
    streamHash should be(hashOf(realBytes))
    streamMac should be(hmacOf(secret, realBytes))
    size should be(bytes.length)
  }
}
