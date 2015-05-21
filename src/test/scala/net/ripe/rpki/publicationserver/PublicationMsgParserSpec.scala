package net.ripe.rpki.publicationserver

class PublicationMsgParserSpec extends PublicationServerBaseSpec {

  val msgParser = new PublicationMessageParser

  def dummyRepo(q: QueryPdu): ReplyPdu = q match {
    case PublishQ(uri, tag, _, txt) => new PublishR(uri, tag)
    case WithdrawQ(uri, tag, _) => new WithdrawR(uri, tag)
  }

  test("should parse publish message") {
    val publishXml = getFile("/publish.xml")
    val msg = msgParser.process(publishXml.mkString, dummyRepo).right.get

    val publishR = msg.pdus.head.asInstanceOf[PublishR]
    publishR.uri should be("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
    publishR.tag should be(None)
  }

  test("should parse publish message with tag") {
    val publishXml = getFile("/publishWithTag.xml")
    val msg = msgParser.process(publishXml.mkString, dummyRepo).right.get

    val publishR = msg.pdus.head.asInstanceOf[PublishR]
    publishR.tag should equal(Some("123"))
  }

  test("should parse withdraw message") {
    val withdrawXml = getFile("/withdraw.xml")
    val msg = msgParser.process(withdrawXml.mkString, dummyRepo).right.get

    val withdrawR = msg.pdus.head.asInstanceOf[WithdrawR]
    withdrawR.uri should be("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
    withdrawR.tag should be(None)
  }

  test("should parse withdraw message with tag") {
    val withdrawXml = getFile("/withdrawWithTag.xml")
    val msg = msgParser.process(withdrawXml.mkString, dummyRepo).right.get

    val withdrawR = msg.pdus.head.asInstanceOf[WithdrawR]
    withdrawR.tag should equal(Some("123"))
  }
}
