package net.ripe.rpki.publicationserver.load

import java.net.{URI, URLEncoder}

import net.ripe.rpki.publicationserver._
import akka.actor._
import spray.http._
import spray.client.pipelining._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Random

class PublicationLoadTest extends PublicationServerBaseTest with Hashing {

  implicit val actorSystem = ActorSystem()

  //  import actorSystem.dispatcher

  val pubServerUri = "https://localhost:7766?clientId=some-client"


  test("shoudl run scenario and be okay") {
    scenario()
  }

  def scenario(): HttpResponse = {
    val publishSet: Seq[PublishQ] = generateObjects(10)
    for (i <- 1 to 2) {
      publish(generateReplaceSet(publishSet, 3))
    }
    publish(generateWithdrawSet(publishSet, 5))
  }


  def publish(pdus: Seq[QueryPdu]) = {
    val msg = Util.xml(pdus: _*).toString()
    val pipeline = sendReceive
    val x = pipeline {
      Post(pubServerUri).withEntity(HttpEntity(msg))
    }
    Await.result(x, Duration.Inf)
  }


  def generateReplaceSet(pdus: Seq[PublishQ], number: Int): Seq[PublishQ] =
    generate(pdus, number) { pdu =>
      PublishQ(pdu.uri, pdu.tag, Some(hash(pdu.base64).hash), randomBase64(4000))
    }

  def generateWithdrawSet(pdus: Seq[PublishQ], number: Int): Seq[WithdrawQ] =
    generate(pdus, number) { pdu =>
      WithdrawQ(pdu.uri, pdu.tag, hash(pdu.base64).hash)
    }

  def generate[T, X](pdus: Seq[T], number: Int)(f: T => X): Seq[X] = {
    val s = pdus.size
    val w = new ListBuffer[X]()

    @tailrec
    def tryGenerate(acc: Int): Seq[X] = {
      var c = acc
      if (acc >= number)
        w.toList
      else {
        pdus.foreach { pdu =>
          val n = r.nextInt(s)
          if (n < number) {
            w += f(pdu)
            c += 1
          }
        }
        tryGenerate(c)
      }
    }

    tryGenerate(0)
  }

  def generateObjects(number: Int): Seq[PublishQ] =
    (1 to number).map { i =>
      val uriLen = r.nextInt(30)
      PublishQ(
        uri = new URI("rsync://host:port/" + generateUriSuffix(uriLen)),
        tag = None,
        hash = None,
        base64 = randomBase64(5000))
    }

  private val r = new Random

  private def randomBase64(len: Int) = {
    val bytes = new Array[Byte](r.nextInt(len))
    r.nextBytes(bytes)
    Base64.encode(bytes)
  }

  private def generateUriSuffix(len: Int): String = {
    val s = (1 to len).map(_ => r.nextPrintableChar())(collection.breakOut)
    URLEncoder.encode(s, "UTF-8")
  }

}
