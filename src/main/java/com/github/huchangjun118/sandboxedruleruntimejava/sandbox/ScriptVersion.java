package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script.AstNode;

/**
 * An immutable, fully prepared release unit of a rule: once constructed the
 * source, AST and metadata never change. Readers either see the previous
 * release or this whole release - never a half-parsed / field-mixed state.
 */
public final class ScriptVersion {

    private final String ruleId;
    private final long version;
    private final String source;
    private final AstNode.Program program;
    private final long committedAtMillis;

    public ScriptVersion(String ruleId, long version, String source,
                         AstNode.Program program, long committedAtMillis) {
        this.ruleId = ruleId;
        this.version = version;
        this.source = source;
        this.program = program;
        this.committedAtMillis = committedAtMillis;
    }

    public String ruleId() {
        return ruleId;
    }

    public long version() {
        return version;
    }

    public String source() {
        return source;
    }

    public AstNode.Program program() {
        return program;
    }

    public long committedAtMillis() {
        return committedAtMillis;
    }
}
