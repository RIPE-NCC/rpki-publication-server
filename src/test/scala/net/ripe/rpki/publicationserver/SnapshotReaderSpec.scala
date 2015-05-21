package net.ripe.rpki.publicationserver

import com.ctc.wstx.exc.WstxValidationException

class SnapshotReaderSpec extends PublicationServerBaseSpec {
  // .rnc can't be handled by Woodstox or Stax. And the only schema that the .rnc can be converted to without loss of information, is .rng ...
  // To convert the rnc from the publication server draft to rng, download a trang.jar from http://www.thaiopensource.com/relaxng/trang.html
  // and execute it like this:
  // java -jar trang.jar -I rnc -O rng schema.rnc schema.rng
  val schema: String = getFile("/rrdp-schema.rng").mkString

  test("should parse and validate snapshot file") {
    val publishXml = getFile("/valid-snapshot.xml")

    val parser = StaxParser.createFor(publishXml.bufferedReader(), schema)

    parser should not be null

    while (parser.hasNext) parser.next

    parser.hasNext should be(false)
  }

  test("should raise an exception when the request is invalid") {
    val invalidXml = getFile("/snapshot-with-invalid-tag.xml")

    val parser = StaxParser.createFor(invalidXml.mkString, schema)

    parser should not be null

    val thrown = intercept[WstxValidationException] {
      while (parser.hasNext) parser.next
    }

    thrown.getMessage should include("tag name \"foo\" is not allowed")
  }

}
