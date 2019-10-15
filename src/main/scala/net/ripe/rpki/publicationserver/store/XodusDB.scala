package net.ripe.rpki.publicationserver.store

import com.typesafe.config.{Config, ConfigFactory}
import jetbrains.exodus.entitystore.{PersistentEntityStore, PersistentEntityStores}
import jetbrains.exodus.env.{EnvironmentConfig, Environments}

object XodusDB {

  var entityStore: PersistentEntityStore = _

  def init(): Unit = {
    val config: Config = ConfigFactory.load()
    val xodusPath: String = config.getString("xodus.path")
    init(xodusPath)
  }

  def init(dbPath: String): Unit = {
    if (entityStore == null) {
      val config = new EnvironmentConfig().
        setLogDurableWrite(true).
        setEnvGatherStatistics(true).
        setGcEnabled(true).
        setLogCacheUseNio(true).
        setEnvCloseForcedly(true).
        setMemoryUsagePercentage(10)

      val env = Environments.newInstance(dbPath, config)
      entityStore = PersistentEntityStores.newInstance(env)
    }
  }
}
