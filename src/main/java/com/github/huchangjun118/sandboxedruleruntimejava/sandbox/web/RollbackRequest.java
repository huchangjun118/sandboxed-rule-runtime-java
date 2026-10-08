package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.web;

/** Rollback request body: point current at a previously committed version. */
public record RollbackRequest(long version) {
}
