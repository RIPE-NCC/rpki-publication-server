package net.ripe.rpki.publicationserver


case class Base64(value: String)

trait MessageParser {

  def Schema: String

}
