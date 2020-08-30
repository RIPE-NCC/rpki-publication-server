package net.ripe.rpki.publicationserver.model

import java.io.OutputStream
import java.net.URI
import java.util.UUID

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.store.postresql.PgStore
import net.ripe.rpki.publicationserver.{AppConfig, Formatting, Hash, Hashing, Logging}
import scalikejdbc.DBSession

case class RrdpWriter(conf: AppConfig) extends Hashing with Formatting with Logging {

  private val pgStore = PgStore.get(conf.pgConfig)

  private[model] def serializeSnapshotTo(sessionId: UUID, serial: Long, stream: OutputStream)(implicit session: DBSession) = {
    IOStream.string(s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    pgStore.readState { (uri, _, bytes) =>
      IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}">""", stream)
      IOStream.string(Bytes.toBase64(bytes).value, stream)
      IOStream.string("</publish>\n", stream)
    }
    IOStream.string("</snapshot>\n", stream)
  }

  private[model] def serializeDeltaTo(sessionId: UUID, serial: Long, stream: OutputStream)(implicit session: DBSession) = {
    IOStream.string(s"""<delta version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    pgStore.readLatestDelta { (operation, uri, oldHash, bytes) =>
      (operation, oldHash, bytes) match {
        case ("INS", None, Some(bytes)) =>
          IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}">""", stream)
          IOStream.string(Bytes.toBase64(bytes).value, stream)
          IOStream.string("</publish>\n", stream)
        case ("UPD", Some(Hash(hash)), Some(bytes)) =>
          IOStream.string(s"""<publish uri="${attr(uri.toASCIIString)}" hash="$hash">""", stream)
          IOStream.string(Bytes.toBase64(bytes).value, stream)
          IOStream.string("</publish>\n", stream)
        case ("DEL", Some(Hash(hash)), None) =>
          IOStream.string(s"""<withdraw uri="${attr(uri.toASCIIString)}" hash="$hash"/>\n""", stream)
        case anythingElse =>
          logger.error(s"Log contains invalid row ${anythingElse}")
      }
    }
    IOStream.string("</delta>\n", stream)
  }



}

