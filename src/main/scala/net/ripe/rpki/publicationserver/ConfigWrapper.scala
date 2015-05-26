package net.ripe.rpki.publicationserver

import com.typesafe.config.ConfigFactory

class ConfigWrapper {
  def getConfig = ConfigWrapper.getConfig
}

object ConfigWrapper {
  val config = ConfigFactory.load()

  def getConfig = config
}
