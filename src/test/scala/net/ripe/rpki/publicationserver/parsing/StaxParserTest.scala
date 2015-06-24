package net.ripe.rpki.publicationserver.parsing

import com.ctc.wstx.exc.WstxValidationException
import net.ripe.rpki.publicationserver.PublicationServerBaseTest

class StaxParserTest extends PublicationServerBaseTest {

  // .rnc can't be handled by Woodstox or Stax. And the only schema that the .rnc can be converted to without loss of information, is .rng ...
  // To convert the rnc from the publication server draft to rng, download a trang.jar from http://www.thaiopensource.com/relaxng/trang.html
  // and execute it like this:
  // java -jar trang.jar -I rnc -O rng schema.rnc schema.rng
  var publicationSchema: String = getFile("/rpki-publication-schema.rng").mkString
  var rrdpSchema: String = getFile("/rrdp-schema.rng").mkString

  test("should parse and validate my publish xml file") {
    val publishXml = getFile("/publish.xml")

    val parser = StaxParser.createFor(publishXml.mkString, publicationSchema)

    parser should not be null

    while (parser.hasNext) parser.next

    parser.hasNext should be(false)
  }

  test("should parse and validate my withdraw xml file") {
    val withdrawXml = getFile("/withdraw.xml")

    val parser = StaxParser.createFor(withdrawXml.mkString, publicationSchema)

    parser should not be null

    while (parser.hasNext) parser.next

    parser.hasNext should be(false)
  }

  test("should raise an exception when the request is invalid") {
    val invalidXml = getFile("/invalidRequest.xml")

    val parser = StaxParser.createFor(invalidXml.mkString, publicationSchema)

    parser should not be null

    val thrown = intercept[WstxValidationException] {
      while (parser.hasNext) parser.next
    }

    thrown.getMessage should include("tag name \"foo\" is not allowed")
  }

  test("should parse and validate snapshot file") {
    val publishXml = getFile("/valid-snapshot.xml")

    val parser = StaxParser.createFor(publishXml.bufferedReader(), rrdpSchema)

    parser should not be null

    while (parser.hasNext) parser.next

    parser.hasNext should be(false)
  }

  test("should throw an exception when snapshot file has invalid element") {
    val invalidXml = getFile("/snapshot-with-invalid-tag.xml")

    val parser = StaxParser.createFor(invalidXml.bufferedReader(), rrdpSchema)

    parser should not be null

    val thrown = intercept[WstxValidationException] {
      while (parser.hasNext) parser.next
    }

    thrown.getMessage should include("tag name \"foo\" is not allowed")
  }
}
