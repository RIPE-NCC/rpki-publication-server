package net.ripe.rpki.publicationserver.repository

import java.net.URI
import java.nio.file.Files

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model._
import net.ripe.rpki.publicationserver.util.Time
import org.scalatest.Ignore

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

@Ignore
class DataFlusherStressTest extends PublicationServerBaseTest with Hashing {

  val rrdpRootDfir = Files.createTempDirectory("test_pub_server_rrdp_")

  val pgStore = createPgStore

  val urlPrefix1 = "rsync://host1.com"

  private val conf = new AppConfig() {
    override lazy val pgConfig = pgTestConfig
    override lazy val rrdpRepositoryPath = rrdpRootDfir.toAbsolutePath.toString
  }

  implicit val healthChecks = new HealthChecks(conf)
  val flusher = new DataFlusher(conf)

  before {
    pgStore.clear()
  }

  def waitForRrdpCleanup() = Thread.sleep(200)

  test("Should initialise an empty RRDP repository with no objects") {
    flusher.initFS()

    val (_, d1) = Time.timed {
      val futures = (1 to 100).map { i =>
        Future {
          (1 to 500).foreach { j =>
            val (bytes, _) = TestBinaries.generateObjectNotBiggerThan(5000)
            val uri = new URI(urlPrefix1 + "/path1/" + i + "/" + j + "/obj.cer")
            pgStore.applyChanges(QueryMessage(Seq(
              PublishQ(uri, tag = None, hash = None, bytes),
            )), ClientId("client_" + i))
          }
        }
      }

      Await.result(Future.sequence(futures), Duration.Inf)
    }

    println(s"Publishing Took ${d1}ms.")

    val (_, d2) = Time.timed(flusher.updateFS())
    println(s"Updating FS took ${d2}ms.")
  }

}
