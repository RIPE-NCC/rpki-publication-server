package net.ripe.rpki.publicationserver

import java.net.URI

import net.ripe.rpki.publicationserver.SnapshotState.SnapshotMap

class SnapshotParserSpec extends PublicationServerBaseSpec {

  test("should parse valid snapshot xml") {
    val result = RrdpParser.parse(getFile("/valid-snapshot.xml"))

    val SnapshotState(sessionId, serial, pdus: SnapshotMap) = result

    sessionId.toString should be("9df4b597-af9e-4dca-bdda-719cce2c4e28")
    serial should be(BigInt(5932))

    val uri1 = new URI("rsync://bandito.ripe.net/repo/77821ba152e5fbd6c46c3e95ac2b27a910a514d5.crl")
    val uri2 = new URI("rsync://bandito.ripe.net/repo/77821ba152e5fbd6c46c3e95ac2b27a910a514d5.mft")
    val uri3 = new URI("rsync://bandito.ripe.net/repo/671570f06499fbd2d6ab76c4f22566fe49d5de60.cer")
    pdus.keys should contain(uri1)
    pdus.keys should contain(uri2)
    pdus.keys should contain(uri3)

    trim(pdus.get(uri1).get._1.value) should equal(trim(
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

    trim(pdus.get(uri2).get._1.value) should equal(trim(
      """MIAGCSqGSIb3DQEHAqCAMIACAQMxDzANBglghkgBZQMEAgEFADCABgsqhkiG9w0BCRABGqCAJIAE
        |        gd0wgdoCAguWGA8yMDE0MTIwMzE4MDgzMloYDzIwMTQxMjA0MTgwODMyWgYJYIZIAWUDBAIBMIGm
        |        MFEWLDY3MTU3MGYwNjQ5OWZiZDJkNmFiNzZjNGYyMjU2NmZlNDlkNWRlNjAuY2VyAyEAh0nT6uSg
        |        nJQhGAnKgjxb9TDeGu9AEd8QK+GHXYop0U8wURYsNzc4MjFiYTE1MmU1ZmJkNmM0NmMzZTk1YWMy
        |        YjI3YTkxMGE1MTRkNS5jcmwDIQAZ658FmRCmFfxCpTfE8hZN00MnUEdohOiISZflCPbrUwAAAAAA
        |        AKCAMIIEcjCCA1qgAwIBAgICC5gwDQYJKoZIhvcNAQELBQAwDTELMAkGA1UEAxMCVEEwHhcNMTQx
        |        MjAzMTgwODMyWhcNMTQxMjEwMTgwODMyWjAzMTEwLwYDVQQDEyhlY2Y0NjhkMDY1MTMyNzFmNTkz
        |        MjZhNjQ2MGZmOTFhYTNiNGU2Njk4MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkiYz
        |        EpnsqHIPNEl/LvJmfZfOYzRlhv0Ewqg/RLi6XsE5dhWi0YAiFLbz0v/PfAjmJJFO6STsXkmc5Cpp
        |        PoAl2+Ffx9Zujzy95hCNMqNgPSsqA92eAStLJALlvWrlYgTQEBV/hIjetDOEY/fL49gajyuKghOh
        |        +zgeEUCVhdIArEj/4j5E1vI7flwJjLP8SI36IWlKoz6cd88Gm8bLQRURAfe2lKW0quJk0RHOnPZk
        |        babWuiiByoU24DCSy1+TBY4mEK6bi1R0iONqeYfaSUrxvWcDh8V6gNikiB+tfwRxrIOO1RTnKnlI
        |        hIe2OC5mkP2gMY5ZUyynJZnS3Or+CY3IcQIDAQABo4IBtDCCAbAwHQYDVR0OBBYEFOz0aNBlEycf
        |        WTJqZGD/kao7TmaYMB8GA1UdIwQYMBaAFHeCG6FS5fvWxGw+lawrJ6kQpRTVMA4GA1UdDwEB/wQE
        |        AwIHgDBFBggrBgEFBQcBAQQ5MDcwNQYIKwYBBQUHMAKGKWh0dHA6Ly9iYW5kaXRvLnJpcGUubmV0
        |        L3Jwa2ktY2EvdGEvdGEuY2VyMGYGCCsGAQUFBwELBFowWDBWBggrBgEFBQcwC4ZKcnN5bmM6Ly9i
        |        YW5kaXRvLnJpcGUubmV0L3JlcG8vNzc4MjFiYTE1MmU1ZmJkNmM0NmMzZTk1YWMyYjI3YTkxMGE1
        |        MTRkNS5tZnQwWwYDVR0fBFQwUjBQoE6gTIZKcnN5bmM6Ly9iYW5kaXRvLnJpcGUubmV0L3JlcG8v
        |        Nzc4MjFiYTE1MmU1ZmJkNmM0NmMzZTk1YWMyYjI3YTkxMGE1MTRkNS5jcmwwGAYDVR0gAQH/BA4w
        |        DDAKBggrBgEFBQcOAjAhBggrBgEFBQcBBwEB/wQSMBAwBgQCAAEFADAGBAIAAgUAMBUGCCsGAQUF
        |        BwEIAQH/BAYwBKACBQAwDQYJKoZIhvcNAQELBQADggEBAAjCpzNzjj7QGhmIG3Elt49cHUJe865w
        |        y2Uq3ZKW2aZgA5It29D07XlsHO8tM0EWVXTxsBbpdkiEnzQ4G8Zx/ZI09vLSJ8ZjzSh42QeMaNt6
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

    trim(pdus.get(uri3).get._1.value) should equal(trim(
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
