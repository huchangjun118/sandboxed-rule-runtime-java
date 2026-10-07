package com.github.huchangjun118.sandboxedruleruntimejava.registry;

import com.github.huchangjun118.sandboxedruleruntimejava.exception.RuleException;

/** Raised on non-monotonic publish versions or rollback to an unknown version. */
public class VersionConflictException extends RuleException {
    public VersionConflictException(String message) {
        super(message);
    }
}
