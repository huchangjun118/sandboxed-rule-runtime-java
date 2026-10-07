package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A bounded output stream for script {@code print} output. Writing beyond the quota
 * raises {@link OutputLimitExceededException} rather than growing without bound, so a
 * chatty script cannot exhaust host memory.
 */
public final class CappedOutputBuffer extends OutputStream {
    private final long limit;
    private final byte[] data;
    private int size;
    private boolean overflowed;

    public CappedOutputBuffer(long limit) {
        this.limit = limit;
        this.data = new byte[(int) Math.min(limit, Integer.MAX_VALUE)];
    }

    @Override
    public void write(int b) throws IOException {
        if (size >= limit) {
            overflowed = true;
            throw new OutputLimitExceededException("script output exceeded " + limit + " bytes");
        }
        data[size++] = (byte) b;
    }

    public boolean isOverflowed() { return overflowed; }

    public String asString() {
        return new String(data, 0, size, StandardCharsets.UTF_8);
    }

    /** Marker used to translate an output overflow into OUTPUT_LIMIT. */
    public static final class OutputLimitExceededException extends IOException {
        public OutputLimitExceededException(String message) { super(message); }
    }
}
