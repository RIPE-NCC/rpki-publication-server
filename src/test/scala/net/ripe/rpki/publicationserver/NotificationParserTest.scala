package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.model.{Notification, SnapshotLocator}
import net.ripe.rpki.publicationserver.parsing.NotificationParser

class NotificationParserTest extends PublicationServerBaseTest {

  test("should parse valid notification.xml") {
    val Right(Notification(sessionId, serial, SnapshotLocator(uri, hash), _)) = NotificationParser.parse(getFile("/notification-valid.xml"))

    sessionId.toString should be("9df4b597-af9e-4dca-bdda-719cce2c4e28")
    serial should be(BigInt(2))

    uri.toString should be("http://rpki.ripe.net/rpki-ca/rrdp/EEEA7F7AD96D85BBD1F7274FA7DA0025984A2AF3D5A0538F77BEC732ECB1B068.xml")
    hash.hash should be("EEEA7F7AD96D85BBD1F7274FA7DA0025984A2AF3D5A0538F77BEC732ECB1B068")
  }
}
