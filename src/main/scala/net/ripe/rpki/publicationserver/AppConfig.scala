package net.ripe.rpki.publicationserver

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration

trait ConfigWrapper {
  def port : Int
  def locationRepositoryPath : String
  def locationRepositoryUri : String
  def locationLogfile : String
  def snapshotRetainPeriod : Duration
}

/**
 * Helper class which can be wired into clients while making sure that the config file is loaded only once.
 */
class AppConfig extends ConfigWrapper {
  def getConfig = AppConfig.config

  lazy val port = getConfig.getInt("port")
  lazy val locationRepositoryPath = getConfig.getString("locations.repository.path")
  lazy val locationRepositoryUri  = getConfig.getString("locations.repository.uri")
  lazy val locationLogfile = getConfig.getString("locations.logfile")
  lazy val snapshotRetainPeriod = Duration(getConfig.getDuration("snapshot.retainPeriod", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
}

object AppConfig {
  lazy val config = ConfigFactory.load()
}
