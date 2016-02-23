package net.ripe.logging

import java.io.{IOException, OutputStream}

import org.apache.log4j.{Priority, Category}

class LoggingOutputStream(category: Category, priority: Priority) extends OutputStream {
  private val LINE_SEPARATOR = System.getProperty("line.separator")
  private var closed = false
  private var buffer = new Array[Byte](2048)
  private var count = 0

  override def close() {
    flush()
    closed = true
  }

  @throws(classOf[IOException])
  override def write(b: Int) {
    if (closed) {
      throw new IOException("The stream has been closed!")
    }
    if (b == 0) {
      return
    }

    if (count == buffer.length) {
      // The buffer is full; grow it
      val newBuffer = new Array[Byte](2 * buffer.length)
      System.arraycopy(buffer, 0, newBuffer, 0, buffer.length)
      buffer = newBuffer
    }

    buffer(count) = b.toByte
    count += 1
  }

  override def flush() {
    if (count == 0) {
      return
    }
    // Don't print out blank lines; flushing from PrintStream puts these out
    if (!isBlankLine) category.log(priority, new String(buffer.slice(0, count)))
    reset()
  }

  private def isBlankLine = (count == LINE_SEPARATOR.length) &&
    (count == 1 && (buffer(0).toChar == LINE_SEPARATOR.charAt(0)) ||
      count == 2 && (buffer(1).toChar == LINE_SEPARATOR.charAt(1)))

  private def reset() {
    count = 0
  }
}
