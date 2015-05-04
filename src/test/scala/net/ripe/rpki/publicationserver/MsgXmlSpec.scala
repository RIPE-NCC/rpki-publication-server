package net.ripe.rpki.publicationserver

import org.specs2.mutable.Specification

class MsgXmlSpec extends Specification with TestFiles {

  "Xml parser" should {

    "parse publish message" in {
      val publishXml = getFile("/publish.xml")
      val msg = MsgXml.parseStream(publishXml.mkString).right.get

      msg.msgType should be(MsgType.query)
      val publishQ = msg.pdus.head.asInstanceOf[PublishQ]
      publishQ.uri must be_==("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
      publishQ.base64.s.trim must startWith("MIIE+jCCA+KgAwIBAgIBDTANBgkqhkiG9w0BAQsFADAzMTEwLwYDVQQDEyhE")
    }

    "parse withdraw message" in {
      val withdrawXml = getFile("/withdraw.xml")
      val msg = MsgXml.parseStream(withdrawXml.mkString).right.get

      msg.msgType should be(MsgType.query)
      val withdrawQ = msg.pdus.head.asInstanceOf[WithdrawQ]
      withdrawQ.uri must be_==("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer")
    }

  }

}
