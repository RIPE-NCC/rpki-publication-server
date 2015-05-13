package net.ripe.rpki.publicationserver

import java.io.StringReader
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants, XMLStreamReader}

import org.codehaus.stax2.validation.{XMLValidationSchema, XMLValidationSchemaFactory}
import org.codehaus.stax2.{XMLStreamReader2, XMLInputFactory2}


class StaxParser(reader: XMLStreamReader) {

  def hasNext: Boolean = reader.hasNext

  def next: StaxEvent = StaxEvent.readFrom(reader)
}

object StaxParser {

  def createFor(xmlString: String, rngString: String): StaxParser = {
    val xmlif: XMLInputFactory2 = XMLInputFactory.newInstance() match {
      case x: XMLInputFactory2 => x
      case _ => throw new ClassCastException
    }
    val reader: XMLStreamReader2 = xmlif.createXMLStreamReader(new StringReader(xmlString)) match {
      case x: XMLStreamReader2 => x
      case _ => throw new ClassCastException
    }

    if (rngString != null) {
      val sf = XMLValidationSchemaFactory.newInstance(XMLValidationSchema.SCHEMA_ID_RELAXNG)
      val rnc = sf.createSchema(new StringReader(rngString))
      reader.validateAgainst(rnc)
    }

    new StaxParser(reader)
  }
}

trait StaxEvent

case class ElementStart(label: String, attrs: Map[String, String]) extends StaxEvent

case class ElementEnd(label: String) extends StaxEvent

case class ElementText(text: String) extends StaxEvent

case class UnknownEvent(code: Int) extends StaxEvent

object StaxEvent {

  def readFrom(reader: XMLStreamReader): StaxEvent = {
    reader.next() match {

      case XMLStreamConstants.START_ELEMENT =>
        val label = reader.getLocalName
        val nrAttrs = reader.getAttributeCount
        val attrs = (0 until nrAttrs).map(i => {
          val attrName = reader.getAttributeLocalName(i)
          val value = reader.getAttributeValue(i)
          attrName -> value
        }).toMap
        new ElementStart(label, attrs)

      case XMLStreamConstants.END_ELEMENT =>
        val label = reader.getLocalName
        new ElementEnd(label)

      case XMLStreamConstants.CHARACTERS =>
        val text = reader.getText()
        new ElementText(text)

      case whatever => new UnknownEvent(whatever)
    }
  }
}

