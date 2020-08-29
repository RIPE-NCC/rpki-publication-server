package net.ripe.rpki

import com.softwaremill.macwire._
import io.prometheus.client.CollectorRegistry
import net.ripe.rpki.publicationserver.metrics.Metrics

package object publicationserver {

  trait RepositoryPath {
    val repositoryPath = wire[AppConfig].rrdpRepositoryPath
  }

  def testMetrics = new Metrics(CollectorRegistry.defaultRegistry)
}
