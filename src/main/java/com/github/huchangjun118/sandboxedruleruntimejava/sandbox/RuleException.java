package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Base type for every failure surfaced by the sandboxed rule runtime.
 *
 * <p>The {@link #reasonCode()} is a stable, machine readable discriminator so
 * callers can tell sandbox policy rejections, script bugs and resource
 * exhaustion apart without parsing messages.</p>
 */
public abstract class RuleException extends RuntimeException {

    protected RuleException(String message) {
        super(message);
    }

    protected RuleException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Stable, machine readable failure discriminator, e.g. {@code SANDBOX_FILESYSTEM}. */
    public abstract String reasonCode();
}
