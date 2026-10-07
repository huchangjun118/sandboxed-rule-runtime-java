package com.github.huchangjun118.sandboxedruleruntimejava.exception;

import com.github.huchangjun118.sandboxedruleruntimejava.model.DenyReason;

/**
 * Thrown when a script attempts a capability the sandbox forbids.
 * The {@link DenyReason} lets callers distinguish file system, process, network,
 * reflection and other denial categories.
 */
public class SandboxDeniedException extends RuntimeException {
    private final DenyReason reason;

    public SandboxDeniedException(DenyReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DenyReason getReason() {
        return reason;
    }
}
