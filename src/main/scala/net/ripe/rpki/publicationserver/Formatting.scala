package net.ripe.rpki.publicationserver

import com.google.common.xml.XmlEscapers

trait Formatting {
  private val attrEscaper = XmlEscapers.xmlAttributeEscaper()
  private val contentEscaper = XmlEscapers.xmlContentEscaper()

  protected def attr(s: String): String = attrEscaper.escape(s)
  protected def content(s: String): String = contentEscaper.escape(s)
}
