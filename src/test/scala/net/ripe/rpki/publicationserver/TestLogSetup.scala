package net.ripe.rpki.publicationserver

trait TestLogSetup {
  System.setProperty("log.file", "publication-server-test.log")
}
