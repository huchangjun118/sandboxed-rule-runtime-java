package com.github.huchangjun118.sandboxedruleruntimejava.model;

import java.util.Map;

/**
 * Structured verdict produced by a script. The script is expected to return an object such as
 * {@code {allowed: true, reason: "...", attributes: {...}}}.
 * Unknown/missing fields are normalised to defaults so callers always see a complete object.
 */
public final class Decision {
    private final boolean allowed;
    private final String reason;
    private final Map<String, Object> attributes;

    public Decision(boolean allowed, String reason, Map<String, Object> attributes) {
        this.allowed = allowed;
        this.reason = reason == null ? "" : reason;
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public boolean isAllowed() { return allowed; }
    public String getReason() { return reason; }
    public Map<String, Object> getAttributes() { return attributes; }
}
