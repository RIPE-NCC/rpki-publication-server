package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Paths, Path}
import java.util.Map.Entry
import java.util.concurrent.TimeUnit

import com.typesafe.config.{ConfigFactory, ConfigObject, ConfigValue}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration

/**
 * Helper class which can be wired into clients while making sure that the config file is loaded only once.
 */
class AppConfig {
  def getConfig = AppConfig.config

  lazy val port = getConfig.getInt("port")
  lazy val rrdpRepositoryPath = getConfig.getString("locations.rrdp.repository.path")
  lazy val rrdpRepositoryUri  = getConfig.getString("locations.rrdp.repository.uri")
  lazy val locationLogfile = getConfig.getString("locations.logfile")
  lazy val unpublishedFileRetainPeriod = Duration(getConfig.getDuration("unpublished-file-retain-period", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  lazy val snapshotSyncDelay = Duration(getConfig.getDuration("snapshot-sync-delay", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  lazy val defaultTimeout = Duration(getConfig.getDuration("default.timeout", TimeUnit.MINUTES), TimeUnit.MINUTES)
  lazy val rsyncRepositoryMapping : Map[URI, Path] = {
    val list : Iterable[ConfigObject] = getConfig.getObjectList("locations.rsync.repository-mapping").asScala
    (for {
      item : ConfigObject <- list
      entry : Entry[String, ConfigValue] <- item.entrySet().asScala
      uri = URI.create(entry.getKey)
      path = Paths.get(entry.getValue.unwrapped().toString)
    } yield (uri, path)).toMap
  }
  lazy val rsyncRepositoryStagingDirName = getConfig.getString("locations.rsync.staging-dir-name")
  lazy val rsyncRepositoryOnlineDirName = getConfig.getString("locations.rsync.online-dir-name")
  lazy val rsyncDirectoryPermissions = getConfig.getString("locations.rsync.directory-permissions")
  lazy val rsyncFilePermissions = getConfig.getString("locations.rsync.file-permissions")
}

object AppConfig {
  lazy val config = ConfigFactory.load()
}
