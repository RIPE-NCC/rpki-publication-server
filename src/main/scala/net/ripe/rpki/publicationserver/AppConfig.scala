package net.ripe.rpki.publicationserver

import java.net.URI
import java.util.Map.Entry
import java.util.concurrent.TimeUnit

import com.typesafe.config.{ConfigFactory, ConfigObject, ConfigValue}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration

trait ConfigWrapper {
  def port : Int
  def rrdpRepositoryPath : String
  def rrdpRepositoryUri : String
  def locationLogfile : String
  def unpublishedFileRetainPeriod : Duration
}

/**
 * Helper class which can be wired into clients while making sure that the config file is loaded only once.
 */
class AppConfig extends ConfigWrapper {
  def getConfig = AppConfig.config

  lazy val port = getConfig.getInt("port")
  lazy val rrdpRepositoryPath = getConfig.getString("locations.rrdp.repository.path")
  lazy val rrdpRepositoryUri  = getConfig.getString("locations.rrdp.repository.uri")
  lazy val locationLogfile = getConfig.getString("locations.logfile")
  lazy val unpublishedFileRetainPeriod = Duration(getConfig.getDuration("unpublished-file-retain-period", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  lazy val defaultTimeout = Duration(getConfig.getDuration("default.timeout", TimeUnit.MINUTES), TimeUnit.MINUTES)
  lazy val rsyncRepositoryMapping : Map[URI, String] = {
    val list : Iterable[ConfigObject] = getConfig.getObjectList("locations.rsync.repository-mapping").asScala
    (for {
      item : ConfigObject <- list
      entry : Entry[String, ConfigValue] <- item.entrySet().asScala
      uri = URI.create(entry.getKey)
      dir = entry.getValue.unwrapped().toString
    } yield (uri, dir)).toMap
  }
}

object AppConfig {
  lazy val config = ConfigFactory.load()
}
