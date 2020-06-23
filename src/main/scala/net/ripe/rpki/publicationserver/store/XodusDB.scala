package net.ripe.rpki.publicationserver.store

import com.typesafe.config.{Config, ConfigFactory}
import jetbrains.exodus.entitystore.{
  PersistentEntityStore,
  PersistentEntityStores
}
import jetbrains.exodus.env.{EnvironmentConfig, Environments}
import net.ripe.rpki.publicationserver.AppConfig
import net.ripe.rpki.publicationserver.Logging

object XodusDB extends Logging {

  var entityStore: PersistentEntityStore = _

  def init(dbPath: String): Unit = {
    if (entityStore == null) {
      val config = new EnvironmentConfig()
        .setLogDurableWrite(true)
        .setEnvGatherStatistics(true)
        .setGcEnabled(true)
        .setLogCacheUseNio(true)
        .setEnvCloseForcedly(true)
        .setMemoryUsagePercentage(10)

      val env = Environments.newInstance(dbPath, config)
      entityStore = PersistentEntityStores.newInstance(env)
    }
  }

  def reset() = {
      entityStore = null;
  }
}
