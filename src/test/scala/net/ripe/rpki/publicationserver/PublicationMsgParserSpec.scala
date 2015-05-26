package net.ripe.rpki.publicationserver

import java.net.URI

class PublicationMsgParserSpec extends PublicationServerBaseSpec {

  val msgParser = new PublicationMessageParser

  def dummyRepo(q: QueryPdu): ReplyPdu = q match {
    case PublishQ(uri, tag, _, txt) => new PublishR(uri, tag)
    case WithdrawQ(uri, tag, _) => new WithdrawR(uri, tag)
  }

  test("should parse publish message") {
    val publishXml = getFile("/publish.xml")
    val msg = msgParser.process(publishXml.mkString).right.get

    val publishQ = msg.pdus.head.asInstanceOf[PublishQ]
    publishQ.uri should equal(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    publishQ.tag should be(None)
  }

  test("should parse publish message with tag") {
    val publishXml = getFile("/publishWithTag.xml")
    val msg = msgParser.process(publishXml.mkString).right.get

    val publishQ = msg.pdus.head.asInstanceOf[PublishQ]
    publishQ.tag should equal(Some("123"))
  }

  test("should parse withdraw message") {
    val withdrawXml = getFile("/withdraw.xml")
    val msg = msgParser.process(withdrawXml.mkString).right.get

    val withdrawQ = msg.pdus.head.asInstanceOf[WithdrawQ]
    withdrawQ.uri should equal(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    withdrawQ.tag should be(None)
  }

  test("should parse withdraw message with tag") {
    val withdrawXml = getFile("/withdrawWithTag.xml")
    val msg = msgParser.process(withdrawXml.mkString).right.get

    val withdrawQ = msg.pdus.head.asInstanceOf[WithdrawQ]
    withdrawQ.tag should equal(Some("123"))
  }
}
