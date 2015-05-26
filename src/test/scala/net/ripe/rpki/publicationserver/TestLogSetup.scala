package net.ripe.rpki.publicationserver

trait TestLogSetup {
  System.setProperty("LOG_FILE", "publication-server-test.log")
}
