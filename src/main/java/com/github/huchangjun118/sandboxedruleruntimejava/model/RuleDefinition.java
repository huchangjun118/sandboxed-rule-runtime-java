package com.github.huchangjun118.sandboxedruleruntimejava.model;

import java.util.Objects;

/**
 * A publish request: immutable rule id, monotonically increasing version number,
 * the JavaScript source and the quota applied to every execution of this version.
 */
public final class RuleDefinition {
    private final String ruleId;
    private final int version;
    private final String source;
    private final ResourceQuota quota;

    public RuleDefinition(String ruleId, int version, String source, ResourceQuota quota) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.version = version;
        this.source = Objects.requireNonNull(source, "source");
        this.quota = Objects.requireNonNull(quota, "quota");
    }

    public String getRuleId() { return ruleId; }
    public int getVersion() { return version; }
    public String getSource() { return source; }
    public ResourceQuota getQuota() { return quota; }
}
