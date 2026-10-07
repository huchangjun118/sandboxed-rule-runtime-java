package com.github.huchangjun118.sandboxedruleruntimejava.model;

/**
 * The outcome of one evaluation: the rule version that actually ran, the decision,
 * any captured (truncated) output, and measured elapsed time in milliseconds.
 */
public final class EvaluationResult {
    private final String ruleId;
    private final int version;
    private final Decision decision;
    private final String output;
    private final boolean outputTruncated;
    private final long elapsedMillis;

    public EvaluationResult(String ruleId, int version, Decision decision,
                            String output, boolean outputTruncated, long elapsedMillis) {
        this.ruleId = ruleId;
        this.version = version;
        this.decision = decision;
        this.output = output;
        this.outputTruncated = outputTruncated;
        this.elapsedMillis = elapsedMillis;
    }

    public String getRuleId() { return ruleId; }
    public int getVersion() { return version; }
    public Decision getDecision() { return decision; }
    public String getOutput() { return output; }
    public boolean isOutputTruncated() { return outputTruncated; }
    public long getElapsedMillis() { return elapsedMillis; }
}
