package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.web;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptVersion;

/**
 * Immutable JSON view of a committed release. The committed AST is never
 * serialized - clients see rule id, version, source and commit time.
 */
public record VersionView(String ruleId, long version, String source, long committedAtMillis) {

    public static VersionView of(ScriptVersion v) {
        return new VersionView(v.ruleId(), v.version(), v.source(), v.committedAtMillis());
    }
}
