package net.ripe.rpki.publicationserver

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class HashingTest extends AnyFunSuite with Matchers with Hashing {
  test("should parse publish message") {
    stringify(null) should be("")
    stringify(Array()) should be("")
    stringify(Array(0x11, 0x22, 0x00, 0xAB, 0xFF).map(_.toByte)) should be("112200abff")
  }
}
