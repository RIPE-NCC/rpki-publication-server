package net.ripe.rpki.publicationserver

import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.Map.Entry
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.settings.ServerSettings
import com.typesafe.config.{ConfigFactory, ConfigObject, ConfigValue}
import net.ripe.rpki.publicationserver.model.{Delta, ServerState}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import com.typesafe.config.Config

/**
 * Helper class which can be wired into clients while making sure that the config file is loaded only once.
 */
class AppConfig {
  def getConfig = AppConfig.config

  lazy val publicationPort = getConfig.getInt("publication.port")
  lazy val rrdpPort = getConfig.getInt("rrdp.port")
  lazy val rrdpRepositoryPath = getConfig.getString("locations.rrdp.repository.path")
  lazy val rrdpRepositoryUri  = getConfig.getString("locations.rrdp.repository.uri")

  lazy val publicationEntitySizeLimit = getConfig.getMemorySize("publication.max-entity-size").toBytes

  lazy val unpublishedFileRetainPeriod = Duration(getConfig.getDuration("unpublished-file-retain-period", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  lazy val snapshotSyncDelay = Duration(getConfig.getDuration("snapshot-sync-delay", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  lazy val defaultTimeout = Duration(getConfig.getDuration("default.timeout", TimeUnit.MINUTES), TimeUnit.MINUTES)
  lazy val serverAddress = if(getConfig.hasPath("server.address")) getConfig.getString("server.address") else "::0"
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

  lazy val publicationServerSettings = Some(ServerSettings(getConfig.getConfig("publication")
                     .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))))

  lazy val publicationServerKeyStoreLocation = getConfig.getString("publication.server.keystore.location")
  lazy val publicationServerKeyStorePassword = getConfig.getString("publication.server.keystore.password")
  lazy val publicationServerTrustStoreLocation = getConfig.getString("publication.server.truststore.location")
  lazy val publicationServerTrustStorePassword = getConfig.getString("publication.server.truststore.password")
  lazy val storePath = getConfig.getString("xodus.path")

  // no delay by default
  lazy val notificationWritingDelay = {
    val field = "publication.server.notification.writing.delay"
    if (getConfig.hasPath(field))
      getConfig.getInt(field)
    else 10
  }

  def snapshotUrl(serverState: ServerState) = {
    val ServerState(sessionId, serial) = serverState
    rrdpRepositoryUri + "/" + sessionId + "/" + serial + "/snapshot.xml"
  }

  def deltaUrl(delta: Delta) : String = deltaUrl(delta.sessionId, delta.serial)

  def deltaUrl(sessionId: UUID, serial: Long) = rrdpRepositoryUri + "/" + sessionId + "/" + serial + "/delta.xml"
}

object AppConfig {
  lazy val config : Config = ConfigFactory.load()
}
