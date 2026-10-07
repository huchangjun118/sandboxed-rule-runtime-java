package com.github.huchangjun118.sandboxedruleruntimejava.exception;

/**
 * Base class for rule management errors (versioning, publish/rollback conflicts).
 */
public class RuleException extends RuntimeException {
    public RuleException(String message) {
        super(message);
    }

    public RuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
