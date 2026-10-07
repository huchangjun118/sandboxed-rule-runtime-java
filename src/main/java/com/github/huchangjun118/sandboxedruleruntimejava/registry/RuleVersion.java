package com.github.huchangjun118.sandboxedruleruntimejava.registry;

import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;

/**
 * An immutable, fully published version snapshot: readers either observe this complete
 * object or the previous one, never a partially built/field-mixed state.
 *
 * <p>It carries a pre-compiled, reusable Graal {@code Source} handle (kept as {@link Object}
 * here to keep this package free of polyglot types) plus the quota and raw source.</p>
 */
public final class RuleVersion {
    private final String ruleId;
    private final int version;
    private final String source;
    private final ResourceQuota quota;
    /** Compiled source handle; opaque to this package. */
    private final Object compiledSource;

    public RuleVersion(String ruleId, int version, String source,
                       ResourceQuota quota, Object compiledSource) {
        this.ruleId = ruleId;
        this.version = version;
        this.source = source;
        this.quota = quota;
        this.compiledSource = compiledSource;
    }

    public String getRuleId() { return ruleId; }
    public int getVersion() { return version; }
    public String getSource() { return source; }
    public ResourceQuota getQuota() { return quota; }
    public Object getCompiledSource() { return compiledSource; }
}
