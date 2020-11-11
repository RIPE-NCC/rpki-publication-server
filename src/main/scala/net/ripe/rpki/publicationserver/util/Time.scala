package net.ripe.rpki.publicationserver.util

object Time {
  def timed[T](f: => T) = {
    val begin = System.currentTimeMillis()
    val r = f
    val end = System.currentTimeMillis()
    (r, end - begin)
  }
}
