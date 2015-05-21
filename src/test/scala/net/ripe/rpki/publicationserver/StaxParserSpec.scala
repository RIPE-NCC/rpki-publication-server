package net.ripe.rpki.publicationserver

import com.ctc.wstx.exc.WstxValidationException

class StaxParserSpec extends PublicationServerBaseSpec {

  var schema: String = _

  before {
    // .rnc can't be handled by Woodstox or Stax. And the only schema that the .rnc could be converted to without loss of information, is .rng ...
    schema = getFile("/schema.rng").mkString
  }

  test("should parse and validate my publish xml file") {
    val publishXml = getFile("/publish.xml")

    val parser = StaxParser.createFor(publishXml.mkString, schema)

    parser should not be null

    while (parser.hasNext) parser.next

    parser.hasNext should be(false)
  }

  test("should parse and validate my withdraw xml file") {
    val withdrawXml = getFile("/withdraw.xml")

    val parser = StaxParser.createFor(withdrawXml.mkString, schema)

    parser should not be null

    while (parser.hasNext) parser.next

    parser.hasNext should be(false)
  }

  test("should raise an exception when the request is invalid") {
    val invalidXml = getFile("/invalidRequest.xml")

    val parser = StaxParser.createFor(invalidXml.mkString, schema)

    parser should not be null

    val thrown = intercept[WstxValidationException] {
      while (parser.hasNext) parser.next
    }

    thrown.getMessage should include("tag name \"foo\" is not allowed")
  }

}