package net.ripe.rpki.publicationserver

import scala.util.{Failure, Success, Try}
import org.scalatest._

class StaxParserSpec extends FunSuite with Matchers with TestFiles {

    test("should parse and validate my xml file") {
      val publishXml = getFile("/publish.xml")

      // .rnc can't be handled by Woodstox or Stax. And the only schema that the .rnc could be converted to without loss of information, is .rng ...
      val schema = getFile("/schema.rng")

      val parser = StaxParser.createFor(publishXml.mkString, schema.mkString)

      parser should not be null

      while (parser.hasNext) parser.next

      parser.hasNext should be(false)
    }

    test("should raise an exception when the request is invalid") {
      val invalidXml = getFile("/invalidRequest.xml")

      val schema = getFile("/schema.rng")

      val parser = StaxParser.createFor(invalidXml.mkString, schema.mkString)

      parser should not be null

      Try (
        while (parser.hasNext) parser.next
      ) match {
        case Success(_) => false
        case Failure(_) => true
      }
    }

}