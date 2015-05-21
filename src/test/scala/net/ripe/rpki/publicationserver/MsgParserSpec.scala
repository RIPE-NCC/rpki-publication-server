package net.ripe.rpki.publicationserver

class MsgParserSpec extends PublicationServerBaseSpec {

  val msgParser = new MsgParser

  def dummyRepo(q: QueryPdu): ReplyPdu = q match {
    case PublishQ(uri, tag, _, txt) => new PublishR(uri, tag)
    case WithdrawQ(uri, tag, _) => new WithdrawR(uri, tag)
  }

  test("should parse publish message") {
    val publishXml = getFile("/publish.xml")
    val msg = msgParser.process(publishXml.mkString, dummyRepo).right.get

    val publishR = msg.pdus.head.asInstanceOf[PublishR]
    publishR.uri should be("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
  }

  test("should parse withdraw message") {
    val withdrawXml = getFile("/withdraw.xml")
    val msg = msgParser.process(withdrawXml.mkString, dummyRepo).right.get

    val withdrawR = msg.pdus.head.asInstanceOf[WithdrawR]
    withdrawR.uri should be("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
  }

}
