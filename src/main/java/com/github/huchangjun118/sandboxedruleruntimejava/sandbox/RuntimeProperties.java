package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized sandbox configuration ({@code sandbox.rule.*}).
 */
@ConfigurationProperties(prefix = "sandbox.rule")
public class RuntimeProperties {

    private int workerThreads = 16;
    private long timeoutMillis = 200L;
    private long memoryBytes = 64L * 1024L;
    private long outputBytes = 8L * 1024L;
    private int maxRecursionDepth = 64;
    private long maxSteps = 2_000_000L;

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public long getMemoryBytes() {
        return memoryBytes;
    }

    public void setMemoryBytes(long memoryBytes) {
        this.memoryBytes = memoryBytes;
    }

    public long getOutputBytes() {
        return outputBytes;
    }

    public void setOutputBytes(long outputBytes) {
        this.outputBytes = outputBytes;
    }

    public int getMaxRecursionDepth() {
        return maxRecursionDepth;
    }

    public void setMaxRecursionDepth(int maxRecursionDepth) {
        this.maxRecursionDepth = maxRecursionDepth;
    }

    public long getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(long maxSteps) {
        this.maxSteps = maxSteps;
    }

    public ResourceLimits toLimits() {
        return new ResourceLimits(timeoutMillis, memoryBytes, outputBytes,
                maxRecursionDepth, maxSteps);
    }
}
