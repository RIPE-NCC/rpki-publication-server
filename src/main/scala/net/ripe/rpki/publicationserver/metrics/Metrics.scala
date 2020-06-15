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
    .name("rpkipublicationserver_objects_published_total")
    .help("Number of objects published by publishing clients")
    .register(registry)

  val countWithdrawnObjects = Counter
    .build()
    .name("rpkipublicationserver_objects_withdrawn_total")
    .help("Number of objects published by publishing clients")
    .register(registry)

  val countFailedToAdd = Counter
    .build()
    .name("rpkipublicationserver_objects_failedtoadd_total")
    .help("Number of failed attempts to add an object (already exists for the given URL)")
    .register(registry)

  val countFailedToReplace = Counter
    .build()
    .name("rpkipublicationserver_objects_failedtoreplace_total")
    .help("Number of failed attempts to replace an object (doesn't exists for the given URL)")
    .register(registry)

  val countFailedToWithdraw = Counter
    .build()
    .name("rpkipublicationserver_objects_failedtowithdraw_total")
    .help("Number of failed attempts to withdraw an object (doesn't exists for the given URL)")
    .register(registry)

  val lastTimeReceived = Gauge
    .build()
    .name("rpkipublicationserver_objects_last_received")
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

  def failedToAdd() = countFailedToAdd.inc()  
  def failedToReplace() = countFailedToReplace.inc()
  def failedToDelete() = countFailedToWithdraw.inc()  

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
