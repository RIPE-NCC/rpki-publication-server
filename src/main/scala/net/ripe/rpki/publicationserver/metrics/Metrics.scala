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

  val countPublishedObjects = Counter
    .build()
    .name("objects_published")
    .help("Number of objects published by publishing clients")
    .register(registry)

  val countWithdrawnObjects = Counter
    .build()
    .name("objects_withdrawn")
    .help("Number of objects published by publishing clients")
    .register(registry)

  val lastTimeReceived = Gauge
    .build()
    .name("last_object_recived")
    .help("Timestamp of last object publication/withdrawal.")
    .register(registry)

  def publishedObject() = {
      countPublishedObjects.inc()
      lastTimeReceived.setToCurrentTime()
  }

  def withdrawnObject() = {
      countWithdrawnObjects.inc()
      lastTimeReceived.setToCurrentTime()
  }

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
