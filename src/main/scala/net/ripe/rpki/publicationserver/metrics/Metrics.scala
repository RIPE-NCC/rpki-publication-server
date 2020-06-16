package net.ripe.rpki.publicationserver.metrics

import java.net.URI

import akka.actor.{Actor, Props, Status}
import akka.actor.{Actor, OneForOneStrategy, Props, Status, SupervisorStrategy}

import akka.http.scaladsl.marshalling.{ToEntityMarshaller, Marshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.messaging.Accumulator
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.ObjectStore.State
import java.io.StringWriter
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client._

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
