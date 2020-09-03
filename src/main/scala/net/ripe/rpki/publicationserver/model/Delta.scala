package net.ripe.rpki.publicationserver.model

import java.util.{Date, UUID}

import net.ripe.rpki.publicationserver.{Hashing, QueryPdu}

case class Delta(sessionId: UUID, serial: Long, pdus: Seq[QueryPdu], whenToDelete: Option[Date] = None) extends Hashing
