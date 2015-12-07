package net.ripe.rpki.publicationserver

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration

trait ConfigWrapper {
  def port : Int
  def locationRepositoryPath : String
  def locationRepositoryUri : String
  def locationLogfile : String
  def unpublishedFileRetainPeriod : Duration
}

/**
 * Helper class which can be wired into clients while making sure that the config file is loaded only once.
 */
class AppConfig extends ConfigWrapper {
  def getConfig = AppConfig.config

  lazy val port = getConfig.getInt("port")
  lazy val locationRepositoryPath = getConfig.getString("locations.rrdp.repository.path")
  lazy val locationRepositoryUri  = getConfig.getString("locations.rrdp.repository.uri")
  lazy val locationLogfile = getConfig.getString("locations.logfile")
  lazy val unpublishedFileRetainPeriod = Duration(getConfig.getDuration("unpublished-file-retain-period", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  lazy val defaultTimeout = Duration(getConfig.getDuration("default.timeout", TimeUnit.MINUTES), TimeUnit.MINUTES)
}

object AppConfig {
  lazy val config = ConfigFactory.load()
}
