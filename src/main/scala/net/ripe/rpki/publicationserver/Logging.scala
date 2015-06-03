package net.ripe.rpki.publicationserver

import org.slf4j.LoggerFactory

trait Logging {
  lazy val logger = LoggerFactory.getLogger(this.getClass)
}
