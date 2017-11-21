package net.ripe.rpki

import com.google.common.io.BaseEncoding
import com.softwaremill.macwire.MacwireMacros._

package object publicationserver {

  case class Base64(value: String)

  object Base64 {

    private val base64 = BaseEncoding.base64()

    def encode(bytes: Array[Byte]): Base64 = Base64(base64.encode(bytes))

    def decode(b64: Base64): Array[Byte] = {
      val Base64(s) = b64
      base64.decode(s)
    }
  }

  trait RepositoryPath {
    val repositoryPath = wire[AppConfig].rrdpRepositoryPath
  }

}
