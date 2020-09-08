package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.Binaries.{Base64, Bytes}
import org.scalatest.{Assertion, FunSuite, Matchers}

class BinariesTest extends FunSuite with Matchers {
  test("should convert to base64 and back") {
    check(Base64("AABB1100"))
    check(Base64("AA=="))
    check(Base64("AABB"))
    check(Base64("AABBCA=="))
    check(Base64("DEADBEEF"))
  }

  private def check(base64: Base64): Assertion = {
    Bytes.toBase64(Bytes.fromBase64(base64)) should be(base64)
  }

}
