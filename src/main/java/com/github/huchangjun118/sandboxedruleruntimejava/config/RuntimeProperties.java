package com.github.huchangjun118.sandboxedruleruntimejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Default sandbox quotas and pool sizing, overridable via application.properties.
 */
@ConfigurationProperties(prefix = "rule.sandbox")
public class RuntimeProperties {
    private int workerThreads = 8;
    private long defaultTimeoutMillis = 2000;
    private long defaultMemoryBytes = 16 * 1024 * 1024;
    private long defaultOutputBytes = 64 * 1024;
    private int defaultRecursionDepth = 500;
    private long defaultStatementLimit = 5_000_000;
    private int historyPerRule = 20;

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int v) { this.workerThreads = v; }
    public long getDefaultTimeoutMillis() { return defaultTimeoutMillis; }
    public void setDefaultTimeoutMillis(long v) { this.defaultTimeoutMillis = v; }
    public long getDefaultMemoryBytes() { return defaultMemoryBytes; }
    public void setDefaultMemoryBytes(long v) { this.defaultMemoryBytes = v; }
    public long getDefaultOutputBytes() { return defaultOutputBytes; }
    public void setDefaultOutputBytes(long v) { this.defaultOutputBytes = v; }
    public int getDefaultRecursionDepth() { return defaultRecursionDepth; }
    public void setDefaultRecursionDepth(int v) { this.defaultRecursionDepth = v; }
    public long getDefaultStatementLimit() { return defaultStatementLimit; }
    public void setDefaultStatementLimit(long v) { this.defaultStatementLimit = v; }
    public int getHistoryPerRule() { return historyPerRule; }
    public void setHistoryPerRule(int v) { this.historyPerRule = v; }
}
