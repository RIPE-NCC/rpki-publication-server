package net.ripe.rpki.publicationserver

import org.scalatest.Inside

class SnapshotReaderSpec extends PublicationServerBaseSpec with Inside {
  // .rnc can't be handled by Woodstox or Stax. And the only schema that the .rnc can be converted to without loss of information, is .rng ...
  // To convert the rnc from the publication server draft to rng, download a trang.jar from http://www.thaiopensource.com/relaxng/trang.html
  // and execute it like this:
  // java -jar trang.jar -I rnc -O rng schema.rnc schema.rng
  val schema: String = getFile("/rrdp-schema.rng").mkString

  test("should parse valid notification.xml") {
    val result = new RrdpMessageParser().process(getFile("/valid-snapshot.xml"))
    inside(result) {
      case SnapshotState(sessionId, serial,
          List(PublishElement(uri1, Hash("124uh"), data1), PublishElement(uri2, Hash("125uh"), data2), PublishElement(uri3, Hash("126uh"), data3))) =>

        sessionId.toString should be("9df4b597-af9e-4dca-bdda-719cce2c4e28")
        serial should be("5932")

        uri1.toString should be("rsync://bandito.ripe.net/repo/77821ba152e5fbd6c46c3e95ac2b27a910a514d5.crl")
        uri2.toString should be("rsync://bandito.ripe.net/repo/77821ba152e5fbd6c46c3e95ac2b27a910a514d5.mft")
        uri3.toString should be("rsync://bandito.ripe.net/repo/671570f06499fbd2d6ab76c4f22566fe49d5de60.cer")

        data1 should be(Base64(
          """
            |        MIIBnzCBiAIBATANBgkqhkiG9w0BAQsFADANMQswCQYDVQQDEwJUQRcNMTQxMjAzMTgwODMyWhcN
            |        MTQxMjA0MTgwODMyWjAVMBMCAguXFw0xNDEyMDMxODA4MzJaoDAwLjAfBgNVHSMEGDAWgBR3ghuh
            |        UuX71sRsPpWsKyepEKUU1TALBgNVHRQEBAICC5YwDQYJKoZIhvcNAQELBQADggEBAFQHr/2ids7e
            |        hmfnX+PmyePSN2EM1fBMLwMud6dqyBF42iNa8N0H/jxMAkgm7SS98TUupZg1aIwqxLwGakFS6VeD
            |        +zCnCGEeMUlXTpZaICDxMxJuJLBOVbqP2amPxWJ22g0+gTXM9KPAoWlNyAiMaNUP+nawjyfMzQ4c
            |        WJjIi1kRHnIhiu9cZwEh9Ns/sC3adPJ8NV6LPpMkQDQvIynxV/fbTf/EwwwRfLy1szGLZSdml4G0
            |        gHohkWaosr4R2A7sZOc/PZGtstqpBRTD8RwVJx0pseC6Zp/01WH/FjzNpXahFPgR1QXy3qBGEHRh
            |        xA08g0+QiGSz+QX5PPQ2dBkTRIY=
            |    """.stripMargin))
        data2 should be(Base64(
          """ZKW2aZgA5It29D07XlsHO8tM0EWVXTxsBbpdkiEnzQ4G8Zx/ZI09vLSJ8ZjzSh42QeMaNt6
            |        6zslilaw9rQcm/5jwxN18BRniwU/oavfRbn36AhfCmpegII/4DTZWjI63wucRrYHTHZm6ZajnHKU
            |        DT1viomKZoZZDAUB4oQ7pN/Mw+t1K9F50VKz+9i3tnVhyt5wVaoEn/4sGRAL680A8Su0MKiyc69t
            |        3DYqnvSgYtFNiBbHhNYooBpraylh5r7WngBxfm+VJYkSaPxU8T6sSz/Capt+1S2UWJGTcFaZl251
            |        bm8nmXcAADGCAawwggGoAgEDgBTs9GjQZRMnH1kyamRg/5GqO05mmDANBglghkgBZQMEAgEFAKBr
            |        MBoGCSqGSIb3DQEJAzENBgsqhkiG9w0BCRABGjAcBgkqhkiG9w0BCQUxDxcNMTQxMjAzMTgwODMy
            |        WjAvBgkqhkiG9w0BCQQxIgQgOUbuFjfSw4aMeIgLlDmT5xI7D05/mH6zVETECtMzWb0wDQYJKoZI
            |        hvcNAQEBBQAEggEAbhfERg8rgzy0GAIPDKj5kNk+owpm7WnRDiUo+6Y30zfKKjFhh1L+N0Ei7b6q
            |        r934eqEoac23wycF/A1e3+d4PoLzvFrm1n9rIia4BaD8GiUle6FEHd5njS7jOt5Kuej64yDFCHtv
            |        ipt8tGFik4MpvEmP5EOhZ1cU/sErvlpdEsxQCaLsb6JUbIvoIHnWGXHE54QXkBvlucUSxypRoqW3
            |        SnAX0vo0F1YNrSDe05So3pjjSmNHOuFFnxZMja+lIMMWTFylbKQJNpLIrb9a/uarfiL9BrGODWqE
            |        dzQh+k3QkTAUojq+YADL+ixO0eg2zpPm+eEU1F2+bGP2M5rbaUfqngAAAAAAAA==
            |    """.stripMargin))
        data3 should be(Base64(
          """
            |        MIIFNDCCBBygAwIBAgIBAjANBgkqhkiG9w0BAQsFADANMQswCQYDVQQDEwJUQTAeFw0xNDExMTMw
            |        MzU4MjlaFw0xNTExMTMwMzU4MjlaMDMxMTAvBgNVBAMTKDY3MTU3MGYwNjQ5OWZiZDJkNmFiNzZj
            |        NGYyMjU2NmZlNDlkNWRlNjAwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDOlUYxDPwu
            |        hqVSG5VXcg96qTYt9aKOH8qV2lAU/jnY1rRl2W5Uoa8RrAIseou8ltLKonMcVulHyoyY+J9GqrzN
            |        45vRSgBaOuvLn6nTuoD0LQsD/m8c/wEmFjQllirxQykLGJLXn1eKdUs/OXGgrAUPzgvkciJdsg69
            |        6X44deHcbCU0ZQZSLxZBZEqjfgyoYgww9n/hK5Sfkb44LsBK1lESdBSRrTpFizrCxl22ptsH0eW4
            |        ek80CV5YgCg4F4u9xlzS2DvB+1X3Nl1vvTZ6TJlpVjIVcvE+sKQ50ntUwWG1+lOJc+twRehhiCAb
            |        yHhfaxID4B+7h5Rcpkh1Q1AUMG9JAgMBAAGjggJ3MIICczAdBgNVHQ4EFgQUZxVw8GSZ+9LWq3bE
            |        8iVm/knV3mAwHwYDVR0jBBgwFoAUd4IboVLl+9bEbD6VrCsnqRClFNUwDwYDVR0TAQH/BAUwAwEB
            |        /zAOBgNVHQ8BAf8EBAMCAQYwRQYIKwYBBQUHAQEEOTA3MDUGCCsGAQUFBzAChilodHRwOi8vYmFu
            |        ZGl0by5yaXBlLm5ldC9ycGtpLWNhL3RhL3RhLmNlcjCCATAGCCsGAQUFBwELBIIBIjCCAR4wVwYI
            |        KwYBBQUHMAWGS3JzeW5jOi8vYmFuZGl0by5yaXBlLm5ldC9yZXBvLzNhODdhNGIxLTZlMjItNGE2
            |        My1hZDBmLTA2ZjgzYWQzY2ExNi9kZWZhdWx0LzCBgwYIKwYBBQUHMAqGd3JzeW5jOi8vYmFuZGl0
            |        by5yaXBlLm5ldC9yZXBvLzNhODdhNGIxLTZlMjItNGE2My1hZDBmLTA2ZjgzYWQzY2ExNi9kZWZh
            |        dWx0LzY3MTU3MGYwNjQ5OWZiZDJkNmFiNzZjNGYyMjU2NmZlNDlkNWRlNjAubWZ0MD0GCCsGAQUF
            |        BzANhjFodHRwOi8vYmFuZGl0by5yaXBlLm5ldC9ycGtpLWNhL25vdGlmeS9ub3RpZnkueG1sMFsG
            |        A1UdHwRUMFIwUKBOoEyGSnJzeW5jOi8vYmFuZGl0by5yaXBlLm5ldC9yZXBvLzc3ODIxYmExNTJl
            |        NWZiZDZjNDZjM2U5NWFjMmIyN2E5MTBhNTE0ZDUuY3JsMBgGA1UdIAEB/wQOMAwwCgYIKwYBBQUH
            |        DgIwHgYIKwYBBQUHAQcBAf8EDzANMAsEAgABMAUDAwDAqDANBgkqhkiG9w0BAQsFAAOCAQEAkAnl
            |        E+Fm1r3cmW8EEwhq4Wo37j7qC8ciU/E/zJqptROd8M8+2PDjCF8K7plf/SqYNUWjCk8zQv7Siala
            |        DP3JNI7oWkJ5K9zSU/qPGD8UbrfK5EF4g+++OAsxsOf/qeMVdZ6FlPIUV0wYj2s9w1zz/r16HFV6
            |        QO785ajB50foqo/oQ74BSRbrlYkWrM8U45rdSiAMlyr0lHgv0OCqNK6AVR6y9Sp6bBUi7RotZ5FN
            |        x0TgBRTA6xp4pjG5FimX1SanMaW1hgYqdc4X5aZ9gPiyqvBcOtFq91WnNTsm5Ox0cPNDCkMPLAwW
            |        pHOiFA0PlD0vBPrvTR1hsgfKGd318Qzq+w==
            |    """.stripMargin)
        )
    }
  }
}
