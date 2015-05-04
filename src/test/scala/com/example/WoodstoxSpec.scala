package com.example

import javax.xml.stream.XMLInputFactory

import org.codehaus.stax2._
import org.codehaus.stax2.validation._
import org.specs2.mutable.Specification

import scala.io.Source

class WoodstoxSpec extends Specification {

  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))

  "woodstox" should {

    "parse and validate my xml file" in {
      val publishXml = getFile("/publish.xml")
      // .rnc can't be handled by woodstox. The only schema that the .rnc could be converted to without loss of information, is .rng ...
      val schema = getFile("/schema.rng")

      val sf = XMLValidationSchemaFactory.newInstance(XMLValidationSchema.SCHEMA_ID_RELAXNG)
      val rnc = sf.createSchema(schema.reader())

      val xmlif:XMLInputFactory2 = XMLInputFactory.newInstance() match {
        case x: XMLInputFactory2 => x
        case _ => throw new ClassCastException
      }
      val reader:XMLStreamReader2 = xmlif.createXMLStreamReader(publishXml.reader()) match {
        case x: XMLStreamReader2 => x
        case _ => throw new ClassCastException
      }

      reader.validateAgainst(rnc)

      while (reader.hasNext) reader.next()

      reader.hasNext must beEqualTo(false)
    }
  }
}