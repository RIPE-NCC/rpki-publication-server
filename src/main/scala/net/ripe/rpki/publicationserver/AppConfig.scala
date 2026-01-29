package net.ripe.rpki.publicationserver

import com.typesafe.config.{Config, ConfigFactory}
import net.ripe.rpki.publicationserver.store.postgresql.{DeltaInfo, SnapshotInfo}
import org.apache.pekko.http.scaladsl.settings.ServerSettings

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * Helper class which can be wired into clients while making sure that the config file is loaded only once.
 */
class AppConfig {
  def getConfig = AppConfig.config

  private lazy val minimumSnapshotObjectsCount = getConfig.getInt("minimum.snapshot.objects.count")
  private lazy val minimumSnapshotObjectsCountEnabled = getConfig.getBoolean("minimum.snapshot.objects.enabled")
  lazy val publicationPort = getConfig.getInt("publication.port")
  lazy val rrdpPort = getConfig.getInt("rrdp.port")
  lazy val rrdpRepositoryPath = getConfig.getString("locations.rrdp.repository.path")
  lazy val rrdpRepositoryUri = getConfig.getString("locations.rrdp.repository.uri")

  lazy val publicationEntitySizeLimit = getConfig.getMemorySize("publication.max-entity-size").toBytes

  lazy val unpublishedFileRetainPeriod = Duration(getConfig.getDuration("unpublished-file-retain-period", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  lazy val defaultTimeout = Duration(getConfig.getDuration("default.timeout", TimeUnit.MINUTES), TimeUnit.MINUTES)
  lazy val serverAddress = if (getConfig.hasPath("server.address")) getConfig.getString("server.address") else "::0"

  lazy val publicationServerSettings = Some(ServerSettings(getConfig.getConfig("publication")
    .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))))

  lazy val publicationServerKeyStoreLocation = getConfig.getString("publication.server.keystore.location")
  lazy val publicationServerKeyStorePassword = getConfig.getString("publication.server.keystore.password")
  lazy val publicationServerTrustStoreLocation = getConfig.getString("publication.server.truststore.location")
  lazy val publicationServerTrustStorePassword = getConfig.getString("publication.server.truststore.password")
  lazy val pgConfig = PgConfig(
    getConfig.getString("postgresql.url"),
    getConfig.getString("postgresql.user"),
    getConfig.getString("postgresql.password")
  )

  def snapshotUrl(snapshotInfo: SnapshotInfo) =
    s"$rrdpRepositoryUri/${snapshotInfo.sessionId}/${snapshotInfo.serial}/${snapshotInfo.name}"

  def deltaUrl(deltaInfo: DeltaInfo) =
    s"$rrdpRepositoryUri/${deltaInfo.sessionId}/${deltaInfo.serial}/${deltaInfo.name}"

  lazy val writeRrdp = isMode("rrdp")

  def isMode(dataType: String) =
    Try(getConfig.getBoolean(s"publication.server.write-$dataType")) match {
      case Failure(_) => true
      case Success(data) => data
    }

  def minimalObjectCount() = {
    Try(getConfig.getInt("minimum.snapshot.objects.count")).map(Some(_)).getOrElse(None)
  }

  lazy val repositoryFlushInterval = FiniteDuration(
    getConfig.getDuration(
      "publication.server.repository-write-interval",
      TimeUnit.MILLISECONDS
    ),
    TimeUnit.MILLISECONDS
  )
}

case class PgConfig(url: String, user: String, password: String)

object AppConfig {
  lazy val config: Config = ConfigFactory.systemEnvironmentOverrides().withFallback(ConfigFactory.load())

  def validate(appConfig: AppConfig) = {
    val minCountEnabled = Try(appConfig.minimumSnapshotObjectsCountEnabled).getOrElse(false)
    if (minCountEnabled) {
      Try(appConfig.minimumSnapshotObjectsCount) match {
        case Failure(_) =>
          throw new IllegalArgumentException("minimum.snapshot.objects.count must be set when minimum.snapshot.objects.count.enabled is true")
        case Success(v) =>
          if (v <= 0) {
            throw new IllegalArgumentException("minimum.snapshot.objects.count must be greater than 0")
          }
      }
    }
    appConfig
  }
}
