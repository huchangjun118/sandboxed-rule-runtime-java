package com.github.huchangjun118.sandboxedruleruntimejava.model;

import java.time.Duration;

/**
 * Per-execution resource limits. Every sandboxed evaluation is bounded by these quotas.
 *
 * <ul>
 *   <li>{@code timeoutMillis}: wall-clock budget for one evaluation.</li>
 *   <li>{@code memoryBytes}: bytes the executing thread may allocate.</li>
 *   <li>{@code outputBytes}: bytes the script may write to its captured output stream.</li>
 *   <li>{@code maxRecursionDepth}: maximum nested guest function call depth.</li>
 *   <li>{@code statementLimit}: maximum guest language statements (budget against tight loops).</li>
 * </ul>
 */
public final class ResourceQuota {
    private final long timeoutMillis;
    private final long memoryBytes;
    private final long outputBytes;
    private final int maxRecursionDepth;
    private final long statementLimit;

    public ResourceQuota(long timeoutMillis, long memoryBytes, long outputBytes,
                         int maxRecursionDepth, long statementLimit) {
        this.timeoutMillis = timeoutMillis;
        this.memoryBytes = memoryBytes;
        this.outputBytes = outputBytes;
        this.maxRecursionDepth = maxRecursionDepth;
        this.statementLimit = statementLimit;
    }

    public long getTimeoutMillis() { return timeoutMillis; }
    public long getMemoryBytes() { return memoryBytes; }
    public long getOutputBytes() { return outputBytes; }
    public int getMaxRecursionDepth() { return maxRecursionDepth; }
    public long getStatementLimit() { return statementLimit; }
    public Duration timeout() { return Duration.ofMillis(timeoutMillis); }
}
