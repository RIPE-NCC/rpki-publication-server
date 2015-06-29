package net.ripe.rpki.publicationserver

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory

/**
 * Helper class which can be wired into clients while making sure that the config file is loaded only once.
 */
class ConfigWrapper {
  def getConfig = ConfigWrapper.config

  lazy val port = getConfig.getInt("port")
  lazy val locationRepositoryPath = getConfig.getString("locations.repository.path")
  lazy val locationRepositoryUri  = getConfig.getString("locations.repository.uri")
  lazy val locationLogfile = getConfig.getString("locations.logfile")
  lazy val snapshotRetainPeriod = getConfig.getDuration("snapshot.retainPeriod", TimeUnit.MILLISECONDS)
}

object ConfigWrapper {
  lazy val config = ConfigFactory.load()
}
