package net.ripe.rpki.publicationserver.parsing

import java.io.{Reader, StringReader}

import javax.xml.stream.{XMLInputFactory, XMLStreamConstants, XMLStreamReader}
import org.codehaus.stax2.validation.{XMLValidationSchema, XMLValidationSchemaFactory}
import org.codehaus.stax2.{XMLInputFactory2, XMLStreamReader2}


class StaxParser(reader: XMLStreamReader) {

  def hasNext: Boolean = reader.hasNext

  def next: StaxEvent = StaxEvent.readFrom(reader)
}

object StaxParser {

  lazy val xmlif: XMLInputFactory2 = XMLInputFactory.newInstance().asInstanceOf[XMLInputFactory2]

  def createFor(xmlString: String, rngString: String): StaxParser = createFor(new StringReader(xmlString), rngString)

  def createFor(streamReader: Reader, rngString: String): StaxParser = {
    val reader: XMLStreamReader2 = createReader(streamReader)

    if (rngString != null) {
      val sf = XMLValidationSchemaFactory.newInstance(XMLValidationSchema.SCHEMA_ID_RELAXNG)
      val rnc = sf.createSchema(new StringReader(rngString))
      reader.validateAgainst(rnc)
    }

    new StaxParser(reader)
  }

  private def createReader(xmlSource: Reader): XMLStreamReader2 = {
    xmlif.createXMLStreamReader(xmlSource).asInstanceOf[XMLStreamReader2]
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
        ElementStart(label, attrs)

      case XMLStreamConstants.END_ELEMENT =>
        ElementEnd(reader.getLocalName)

      case XMLStreamConstants.CHARACTERS =>
        ElementText(reader.getText)

      case whatever =>
        UnknownEvent(whatever)
    }
  }
}

