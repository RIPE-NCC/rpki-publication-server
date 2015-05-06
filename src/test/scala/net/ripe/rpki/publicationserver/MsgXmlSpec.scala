package net.ripe.rpki.publicationserver

import org.scalatest.{FunSuite, Matchers}

class MsgXmlSpec extends FunSuite with Matchers with TestFiles {

  def dummyRepo(q: QueryPdu): ReplyPdu = q match {
    case PublishQ(uri, txt) => new PublishR(uri)
    case WithdrawQ(uri) => new WithdrawR(uri)
  }

  test("should parse publish message") {
    val publishXml = getFile("/publish.xml")
    val msg = MsgXml.process(publishXml.mkString, dummyRepo).right.get

    val publishR = msg.pdus.head.asInstanceOf[PublishR]
    publishR.uri should be("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
    //publishQ.base64.s.trim should startWith("MIIE+jCCA+KgAwIBAgIBDTANBgkqhkiG9w0BAQsFADAzMTEwLwYDVQQDEyhE")
  }

  test("should parse withdraw message") {
    val withdrawXml = getFile("/withdraw.xml")
    val msg = MsgXml.process(withdrawXml.mkString, dummyRepo).right.get

    val withdrawR = msg.pdus.head.asInstanceOf[WithdrawR]
    //withdrawQ.uri should be("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
  }

}
