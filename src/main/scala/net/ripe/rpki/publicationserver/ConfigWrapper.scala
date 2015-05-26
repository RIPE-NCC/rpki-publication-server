package net.ripe.rpki.publicationserver

import com.typesafe.config.ConfigFactory

/**
 * Helper class which can be wired into clients while making sure that the config file is loaded only once.
 */
class ConfigWrapper {
  def getConfig = ConfigWrapper.getConfig
}

object ConfigWrapper {
  val config = ConfigFactory.load()

  def getConfig = config
}
