package net.ripe.rpki.publicationserver.model

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.Hashing

case class Snapshot(serverState: ServerState, pdus: Seq[(Bytes, URI)]) extends Hashing

