package net.ripe.rpki.publicationserver

import org.specs2.mutable.Specification

class StaxParserSpec extends Specification with TestFiles {

  "StaxParser" should {

    "parse and validate my xml file" in {
      val publishXml = getFile("/publish.xml")
      // .rnc can't be handled by Woodstox or Stax. And the only schema that the .rnc could be converted to without loss of information, is .rng ...
      val schema = getFile("/schema.rng")

      val parser = StaxParser.createFor(publishXml.mkString, schema.mkString)

      parser must not be null

      while (parser.hasNext) parser.next

      parser.hasNext must beEqualTo(false)
    }
  }
}