package net.ripe.logging;

import org.apache.log4j.Category;
import org.apache.log4j.Priority;

import java.io.IOException;
import java.io.OutputStream;

public class LoggingOutputStream extends OutputStream {
    protected static final String LINE_SEPARATOR = System.getProperty("line.separator");

    protected boolean hasBeenClosed = false;

    protected byte[] buf;

    protected int count;

    private int bufLength;

    public static final int DEFAULT_BUFFER_LENGTH = 2048;

    protected Category category;

    protected Priority priority;

    public LoggingOutputStream(Category cat, Priority priority)
        throws IllegalArgumentException {
        if (cat == null) {
            throw new IllegalArgumentException("cat == null");
        }
        if (priority == null) {
            throw new IllegalArgumentException("priority == null");
        }

        this.priority = priority;
        category = cat;
        bufLength = DEFAULT_BUFFER_LENGTH;
        buf = new byte[DEFAULT_BUFFER_LENGTH];
        count = 0;
    }

    public void close() {
        flush();
        hasBeenClosed = true;
    }

    public void write(final int b) throws IOException {
        if (hasBeenClosed) {
            throw new IOException("The stream has been closed.");
        }

        // don't log nulls
        if (b == 0) {
            return;
        }

        // would this be writing past the buffer?
        if (count == bufLength) {
            // grow the buffer
            final int newBufLength = bufLength + DEFAULT_BUFFER_LENGTH;
            final byte[] newBuf = new byte[newBufLength];

            System.arraycopy(buf, 0, newBuf, 0, bufLength);

            buf = newBuf;
            bufLength = newBufLength;
        }

        buf[count] = (byte) b;
        count++;
    }

    public void flush() {
        if (count == 0) {
            return;
        }

        // don't print out blank lines; flushing from PrintStream puts out these
        if (count == LINE_SEPARATOR.length()) {
            if (((char) buf[0]) == LINE_SEPARATOR.charAt(0) &&
                ((count == 1) ||  // <- Unix & Mac, -> Windows
                    ((count == 2) && ((char) buf[1]) == LINE_SEPARATOR.charAt(1)))) {
                reset();
                return;
            }
        }

        final byte[] theBytes = new byte[count];

        System.arraycopy(buf, 0, theBytes, 0, count);

        category.log(priority, new String(theBytes));

        reset();
    }

    private void reset() {
        // not resetting the buffer -- assuming that if it grew that it
        //   will likely grow similarly again
        count = 0;
    }
}

