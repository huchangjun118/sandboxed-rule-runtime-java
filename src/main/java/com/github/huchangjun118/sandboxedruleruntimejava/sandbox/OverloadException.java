package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Raised when the shared execution pool cannot accept a new execution.
 * Fast scripts are never queued behind slow ones beyond the configured pool;
 * overflow is rejected explicitly instead.
 */
public class OverloadException extends RuleException {

    public OverloadException(String message) {
        super(message);
    }

    @Override
    public String reasonCode() {
        return "RUNTIME_OVERLOADED";
    }
}
