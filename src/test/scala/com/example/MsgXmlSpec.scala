package com.example

import org.specs2.mutable.Specification

class MsgXmlSpec extends Specification {

  "Xml parser should" should {

    "Parse a message" in {
      val xml =
        """
          |<msg type="query" version="3" xmlns="http://www.hactrn.net/uris/rpki/publication-spec/">
          |  <publish uri="rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer">BLABLA</publish>
          |</msg>
        """.stripMargin

      val msg: Msg = MsgXml.parse(xml)

      msg.msgType should be(MsgType.query)
      msg.pdus should be(Seq(Publish("rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer", Base64("BLABLA"))))
    }

  }

}
