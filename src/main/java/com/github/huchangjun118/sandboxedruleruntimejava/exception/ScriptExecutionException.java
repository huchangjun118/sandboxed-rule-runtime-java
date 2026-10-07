package com.github.huchangjun118.sandboxedruleruntimejava.exception;

import com.github.huchangjun118.sandboxedruleruntimejava.model.DenyReason;

/**
 * Raised when a single rule evaluation fails or is aborted by the sandbox.
 * Carries a stable {@link ExecutionErrorKind} and, for denials, the {@link DenyReason}.
 */
public class ScriptExecutionException extends RuntimeException {
    private final ExecutionErrorKind kind;
    private final DenyReason denyReason;

    public ScriptExecutionException(ExecutionErrorKind kind, String message) {
        this(kind, null, message, null);
    }

    public ScriptExecutionException(ExecutionErrorKind kind, String message, Throwable cause) {
        this(kind, null, message, cause);
    }

    public ScriptExecutionException(ExecutionErrorKind kind, DenyReason denyReason, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.denyReason = denyReason;
    }

    public ExecutionErrorKind getKind() {
        return kind;
    }

    public DenyReason getDenyReason() {
        return denyReason;
    }
}
