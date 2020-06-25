package net.ripe.rpki.publicationserver.parsing

import java.net.URI

import net.ripe.rpki.publicationserver._

class PublicationMsgParserTest extends PublicationServerBaseTest {

  val msgParser = new PublicationMessageParser

  before {
    initStore()
  }
  after {
    cleanStore()
  }
  def dummyRepo(q: QueryPdu): ReplyPdu = q match {
    case PublishQ(uri, tag, _, _) => PublishR(uri, tag)
    case WithdrawQ(uri, tag, _) => WithdrawR(uri, tag)
  }

  test("should parse publish message") {
    val publishXml = getFile("/publish.xml")
    val msg = msgParser.parse(publishXml).right.get.asInstanceOf[QueryMessage]

    val publishQ = msg.pdus.head.asInstanceOf[PublishQ]
    publishQ.uri should equal(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    publishQ.tag should be(None)
  }

  test("should parse publish message with tag") {
    val publishXml = getFile("/publishWithTag.xml")
    val msg = msgParser.parse(publishXml).right.get.asInstanceOf[QueryMessage]

    val publishQ = msg.pdus.head.asInstanceOf[PublishQ]
    publishQ.tag should equal(Some("123"))
  }

  test("should parse withdraw message") {
    val withdrawXml = getFile("/withdraw.xml")
    val msg = msgParser.parse(withdrawXml).right.get.asInstanceOf[QueryMessage]

    val withdrawQ = msg.pdus.head.asInstanceOf[WithdrawQ]
    withdrawQ.uri should equal(new URI("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer"))
    withdrawQ.tag should be(None)
  }

  test("should parse withdraw message with tag") {
    val withdrawXml = getFile("/withdrawWithTag.xml")
    val msg = msgParser.parse(withdrawXml).right.get.asInstanceOf[QueryMessage]

    val withdrawQ = msg.pdus.head.asInstanceOf[WithdrawQ]
    withdrawQ.tag should equal(Some("123"))
  }

  test("should parse list message") {
    val listXml = getFile("/list.xml")
    val msg = msgParser.parse(listXml).right.get
    msg.isInstanceOf[ListMessage] should be(true)
  }

  test("should parse list message in case it contains other PDUs") {
    val listXml = getFile("/dubiousListRequest.xml")
    val msg = msgParser.parse(listXml).right.get
    msg.isInstanceOf[ListMessage] should be(true)
  }
}
