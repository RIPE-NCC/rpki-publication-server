package net.ripe.rpki.publicationserver.fs

import java.io.{FileWriter, File}

import scala.util.{Success, Failure, Try}

trait FsAction[T] {
  def execute(): Try[T]

  def rollback(): Unit

  def onSuccess[X](f: T => Try[X]): Try[X] =
    execute().transform(f, { e => rollback(); Failure(e) })
}

case class WriteFile(file: File, content: String) extends FsAction[Unit] {
  override def execute() = Try {
    val writer = new FileWriter(file)
    try writer.write(content)
    finally writer.close()
  }

  override def rollback() = Try {
    file.delete()
  }
}

case class MkDir[X](dirName: String)(f: File => Try[X]) extends FsAction[File]() {
  override def execute() = Try {
    val dir = new File(dirName)
    if (!dir.exists()) dir.mkdir()
    dir
  }

  override def rollback() = Try {
    new File(dirName).delete()
  }
}

object FsAction {
  def execute[T](actions: List[FsAction[T]]) = {

    def step[T](actions: List[FsAction[T]]): Try[T] = {
      actions match {
        case a :: Nil => a.execute()
        case a :: rest => a.execute() match {
          case s@Success(_) => s.flatMap(_ => step(rest))
          case Failure(f) =>
            a.rollback()
            Failure(f)
        }
      }
    }
  }

}

