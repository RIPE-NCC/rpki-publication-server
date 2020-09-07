package net.ripe.logging

/*
 * The code below is copied from http://stackoverflow.com/a/11187462
 */

import java.io.{IOException, PrintStream}

import org.slf4j.{Logger, LoggerFactory}

object SysStreamsLogger {
    private val sysOutLogger: Logger = LoggerFactory.getLogger("SYSOUT")
    private val sysErrLogger: Logger = LoggerFactory.getLogger("SYSERR")
    val sysout: PrintStream = System.out
    val syserr: PrintStream = System.err
    protected val LINE_SEPERATOR: String = System.getProperty("line.separator")

    def bindSystemStreams() {
        System.setOut(new PrintStream(new LoggingOutputStream(sysOutLogger, false), true))
        System.setErr(new PrintStream(new LoggingOutputStream(sysErrLogger, true), true))
    }

    def unbindSystemStreams() {
        System.setOut(sysout)
        System.setErr(syserr)
    }

    private object LoggingOutputStream {
        /**
          * The default number of bytes in the buffer. =2048
          */
        val DEFAULT_BUFFER_LENGTH: Int = 2048
    }

    /**
      * Creates the LoggingOutputStream to flush to the given Category.
      *
      * @param log
      * the Logger to write to
      * @param isError
      * the if true write to error, else info
      * @throws IllegalArgumentException
      *            if cat == null or priority == null
      */
    private class LoggingOutputStream(log: Logger, isError: Boolean) extends java.io.OutputStream {

        if (log == null) {
            throw new IllegalArgumentException("log == null")
        }

        /**
          * Used to maintain the contract of {@link #close()}.
          */
        protected var hasBeenClosed: Boolean = false

        /**
          * The internal buffer where data is stored.
          */
        protected var buf: Array[Byte] = new Array[Byte](LoggingOutputStream.DEFAULT_BUFFER_LENGTH)

        /**
          * The number of valid bytes in the buffer. This value is always in the
          * range <tt>0</tt> through <tt>buf.length</tt>; elements
          * <tt>buf[0]</tt> through <tt>buf[count-1]</tt> contain valid byte
          * data.
          */
        protected var count: Int = 0

        /**
          * Remembers the size of the buffer for speed.
          */
        private var bufLength: Int = LoggingOutputStream.DEFAULT_BUFFER_LENGTH

        /**
          * Closes this output stream and releases any system resources
          * associated with this stream. The general contract of
          * <code>close</code> is that it closes the output stream. A closed
          * stream cannot perform output operations and cannot be reopened.
          */
        override def close() {
            flush()
            hasBeenClosed = true
        }

        /**
          * Writes the specified byte to this output stream. The general contract
          * for <code>write</code> is that one byte is written to the output
          * stream. The byte to be written is the eight low-order bits of the
          * argument <code>b</code>. The 24 high-order bits of <code>b</code> are
          * ignored.
          *
          * @param b
          * the <code>byte</code> to write
          */
        @throws(classOf[IOException])
        def write(b: Int): Unit = {
            if (hasBeenClosed) throw new IOException("The stream has been closed.")
            if (b != 0) {
                if (count == bufLength) {
                    val newBufLength: Int = bufLength + LoggingOutputStream.DEFAULT_BUFFER_LENGTH
                    val newBuf: Array[Byte] = new Array[Byte](newBufLength)
                    System.arraycopy(buf, 0, newBuf, 0, bufLength)
                    buf = newBuf
                    bufLength = newBufLength
                }
                buf(count) = b.toByte
                count += 1
            }
        }

        /**
          * Flushes this output stream and forces any buffered output bytes to be
          * written out. The general contract of <code>flush</code> is that
          * calling it is an indication that, if any bytes previously written
          * have been buffered by the implementation of the output stream, such
          * bytes should immediately be written to their intended destination.
          */
        override def flush() {
            if (count != 0) {
                if (count == LINE_SEPERATOR.length) {
                    if (buf(0).toChar == LINE_SEPERATOR.charAt(0) && ((count == 1) || ((count == 2) && buf(1).toChar == LINE_SEPERATOR.charAt(1)))) {
                        reset()
                        return
                    }
                }
                val theBytes: Array[Byte] = new Array[Byte](count)
                System.arraycopy(buf, 0, theBytes, 0, count)
                if (isError) log.error(new String(theBytes))
                else log.info(new String(theBytes))
                reset()
            }
        }

        private def reset() {
            count = 0
        }
    }
}
