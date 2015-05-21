package net.ripe.rpki.publicationserver


case class Base64(s: String)

trait MessageParser {

  def Schema: String

}
