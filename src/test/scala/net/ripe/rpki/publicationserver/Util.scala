package net.ripe.rpki.publicationserver

import scala.xml.Elem

object Util {

  def xmlSeq(pdus: Seq[QueryPdu]): Elem = xml(pdus: _*)

  def xml(pdus: QueryPdu*): Elem =
    <msg type="query" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
      {pdus.map {
      case PublishQ(uri, None, None, Base64(b)) =>
        <publish uri={uri.toString}>
          {b}
        </publish>

      case PublishQ(uri, None, Some(hash), Base64(b)) =>
        <publish uri={uri.toString} hash={hash}>
          {b}
        </publish>

      case PublishQ(uri, Some(tag), None, Base64(b)) =>
        <publish uri={uri.toString} tag={tag}>
          {b}
        </publish>

      case PublishQ(uri, Some(tag), Some(hash), Base64(b)) =>
        <publish uri={uri.toString} hash={hash} tag={tag}>
          {b}
        </publish>

      case WithdrawQ(uri, None, hash) =>
          <withdraw uri={uri.toString} hash={hash}/>

      case WithdrawQ(uri, Some(tag), hash) =>
          <withdraw uri={uri.toString} hash={hash} tag={tag}/>

      case ListQ(None) => <list/>
      case ListQ(Some(tag)) => <list tag={tag}/>
    }}
    </msg>


}
