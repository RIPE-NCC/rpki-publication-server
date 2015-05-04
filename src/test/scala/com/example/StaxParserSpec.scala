package com.example

import org.specs2.mutable.Specification

import scala.io.Source

class StaxParserSpec extends Specification {

  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))

  "staxParser" should {

    "parse and validate my xml file" in {
      val publishXml = getFile("/publish.xml")
      // .rnc can't be handled by woodstox. The only schema that the .rnc could be converted to without loss of information, is .rng ...
      val schema = getFile("/schema.rng")

      val parser = StaxParser.createFor(publishXml.mkString, schema.mkString)

      parser must not be null

      while (parser.hasNext) parser.next

      parser.hasNext must beEqualTo(false)
    }
  }
}