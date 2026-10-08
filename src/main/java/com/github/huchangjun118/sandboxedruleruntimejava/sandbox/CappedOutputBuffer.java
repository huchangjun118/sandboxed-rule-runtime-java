package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A bounded sink for script {@code print}/{@code console.log} output.
 *
 * <p>GraalJS's built-in {@code print} swallows {@link java.io.IOException} raised by the
 * output stream, so this buffer does <em>not</em> rely on an exception propagating. It
 * retains at most the configured number of bytes and sets a sticky {@code overflowed}
 * flag. The engine checks that flag after the script returns and aborts the evaluation
 * with {@code OUTPUT_LIMIT}, guaranteeing a chatty script can never exhaust host memory
 * and never silently exceeds its output quota.</p>
 */
public final class CappedOutputBuffer extends OutputStream {
    private final int limit;
    private byte[] data;
    private int size;
    private boolean overflowed;

    public CappedOutputBuffer(long limit) {
        int cap = (int) Math.min(Math.max(limit, 1), Integer.MAX_VALUE);
        this.limit = cap;
        // Grow lazily; a script that never prints allocates nothing large.
        this.data = new byte[Math.min(cap, 4096)];
    }

    @Override
    public void write(int b) {
        if (size >= limit) {
            overflowed = true;
            return;
        }
        ensureCapacity(size + 1);
        data[size++] = (byte) b;
    }

    @Override
    public void write(byte[] bytes, int off, int len) {
        if (len <= 0) {
            return;
        }
        long projected = (long) size + len;
        if (projected > limit) {
            overflowed = true;
        }
        int allowed = (int) Math.max(0, limit - size);
        if (allowed > 0) {
            ensureCapacity(size + allowed);
            System.arraycopy(bytes, off, data, size, allowed);
            size += allowed;
        }
    }

    private void ensureCapacity(int needed) {
        if (needed <= data.length) {
            return;
        }
        int grown = Math.max(needed, Math.min(limit, data.length * 2));
        byte[] bigger = new byte[grown];
        System.arraycopy(data, 0, bigger, 0, size);
        data = bigger;
    }

    public boolean isOverflowed() {
        return overflowed;
    }

    /** The retained prefix (never larger than the quota). */
    public String asString() {
        return new String(data, 0, size, StandardCharsets.UTF_8);
    }

    public long getLimit() {
        return limit;
    }
}
