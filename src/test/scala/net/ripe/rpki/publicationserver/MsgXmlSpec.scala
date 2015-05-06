package net.ripe.rpki.publicationserver

import org.scalatest.{FunSuite, Matchers}

class MsgXmlSpec extends FunSuite with Matchers with TestFiles {

  test("should parse publish message") {
    val publishXml = getFile("/publish.xml")
    val msg = MsgXml.parseStream(publishXml.mkString).right.get

    msg.msgType should be(MsgType.query)
    val publishQ = msg.pdus.head.asInstanceOf[PublishQ]
    publishQ.uri should be("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
    publishQ.base64.s.trim should startWith("MIIE+jCCA+KgAwIBAgIBDTANBgkqhkiG9w0BAQsFADAzMTEwLwYDVQQDEyhE")
  }

  test("should parse withdraw message") {
    val withdrawXml = getFile("/withdraw.xml")
    val msg = MsgXml.parseStream(withdrawXml.mkString).right.get

    msg.msgType should be(MsgType.query)
    val withdrawQ = msg.pdus.head.asInstanceOf[WithdrawQ]
    withdrawQ.uri should be("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
  }

}
