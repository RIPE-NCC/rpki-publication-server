package net.ripe.rpki.publicationserver

import net.ripe.rpki.publicationserver.store.{Migrations, DBConfig}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, Matchers, FunSuite}
import spray.http.HttpHeaders.RawHeader
import spray.http.{HttpMethods, HttpRequest}

import scala.io.Source

abstract class PublicationServerBaseTest extends FunSuite with BeforeAndAfter with Matchers with MockitoSugar with TestLogSetup {

  DBConfig.useMemoryDatabase = true
  Migrations.migrate()

  def getFile(fileName: String) = Source.fromURL(getClass.getResource(fileName))

  def trim(s: String): String = s.filterNot(_.isWhitespace)

  def POST(uriString: String, content: String) = HttpRequest(
    method = HttpMethods.POST,
    uri = uriString,
    headers = List(RawHeader("Content-type", "application/rpki-publication")),
    entity = content)

  def xml(pdus: Seq[QueryPdu]) =
    <msg
    type="query"
    version="3"
    xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      {pdus.map {
      case PublishQ(uri, None, None, Base64(b)) =>
        <publish uri={uri.toString}>{b}</publish>

      case PublishQ(uri, None, Some(hash), Base64(b)) =>
        <publish uri={uri.toString} hash={hash}>{b}</publish>

      case PublishQ(uri, Some(tag), None, Base64(b)) =>
        <publish uri={uri.toString} tag={tag}>{b}</publish>

      case PublishQ(uri, Some(tag), Some(hash), Base64(b)) =>
        <publish uri={uri.toString} hash={hash} tag={tag}>{b}</publish>

      case WithdrawQ(uri, None, hash) =>
          <withdraw uri={uri.toString} hash={hash}/>

      case WithdrawQ(uri, Some(tag), hash) =>
          <withdraw uri={uri.toString} hash={hash} tag={tag}/>

      case ListQ(None) => <list/>
      case ListQ(Some(tag)) => <list tag={tag}/>
    }}
    </msg>

}
