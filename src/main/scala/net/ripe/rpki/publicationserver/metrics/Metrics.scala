package net.ripe.rpki.publicationserver.metrics

import java.io.StringWriter

import org.apache.pekko.http.scaladsl.server.Directives._
import io.prometheus.client._
import io.prometheus.client.exporter.common.TextFormat

object Metrics {
    var metrics : Map[CollectorRegistry, Metrics] = Map()

    // this is to have sigleton behaviour for metrics, i.e.e
    // only one object of 'Metrics' per object of 'CollectorRegistry'.
    // In practice there will be only one, but it is useful for tests.
    def get(registry: CollectorRegistry) : Metrics = {
        synchronized {
            metrics.get(registry) match {
                case None => 
                    val m = new Metrics(registry)
                    metrics = metrics + (registry -> m)
                    m
                case Some(m) => m
            }
        }
    }
}

class Metrics(val registry: CollectorRegistry) {

  val countObjectOperations = Counter
    .build()
    .name("rpkipublicationserver_object_operations_total")
    .labelNames("operation")
    .help("Number of objects from publishing clients")
    .register(registry)

  val countFailures = Counter
    .build()
    .name("rpkipublicationserver_objects_failure_total")
    .labelNames("operation")
    .help("Number of failed (mutation) operations attempts by operation")
    .register(registry)

  val lastTimeReceived = Gauge
    .build()
    .name("rpkipublicationserver_objects_last_received")
    .labelNames("operation")
    .help("Timestamp of last object publication/withdrawal.")
    .register(registry)

  def publishedObject() = {
      countObjectOperations.labels("publish").inc()
      lastTimeReceived.labels("publish").setToCurrentTime()
  }

  def withdrawnObject() = {
      countObjectOperations.labels("withdraw").inc()
      lastTimeReceived.labels("withdraw").setToCurrentTime()
  }

  def failedToAdd() = countFailures.labels("add").inc()
  def failedToReplace() = countFailures.labels("replace").inc()
  def failedToDelete() = countFailures.labels("delete").inc()

}

class MetricsApi(val registry: CollectorRegistry) {
  val routes = {
    get {
      path("metrics") {
        complete {
          val writer = new StringWriter()
          TextFormat.write004(writer, registry.metricFamilySamples())
          writer.toString
        }
      }
    }
  }
}
