package com.github.huchangjun118.sandboxedruleruntimejava.web.dto;

/** REST body for publishing a new rule version. */
public class PublishRequest {
    private int version;
    private String source;
    private Long timeoutMillis;
    private Long memoryBytes;
    private Long outputBytes;
    private Integer recursionDepth;
    private Long statementLimit;

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(Long timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public Long getMemoryBytes() { return memoryBytes; }
    public void setMemoryBytes(Long memoryBytes) { this.memoryBytes = memoryBytes; }
    public Long getOutputBytes() { return outputBytes; }
    public void setOutputBytes(Long outputBytes) { this.outputBytes = outputBytes; }
    public Integer getRecursionDepth() { return recursionDepth; }
    public void setRecursionDepth(Integer recursionDepth) { this.recursionDepth = recursionDepth; }
    public Long getStatementLimit() { return statementLimit; }
    public void setStatementLimit(Long statementLimit) { this.statementLimit = statementLimit; }
}
