package com.github.huchangjun118.sandboxedruleruntimejava.model;

/**
 * Distinguishable reasons why an access to a host capability was rejected by the sandbox,
 * before or during script execution.
 */
public enum DenyReason {
    /** Access to the local file system (read/write/path queries). */
    FILE_SYSTEM,
    /** Spawning or interacting with operating-system processes. */
    PROCESS,
    /** Opening sockets or any network endpoint. */
    NETWORK,
    /** Reflection, class loading and arbitrary host class access. */
    REFLECTION,
    /** Creating new threads / thread groups. */
    THREAD,
    /** Native libraries / FFI / unsafe memory access. */
    NATIVE,
    /** Accessing process environment variables. */
    ENVIRONMENT,
    /** Accessing other installed polyglot languages/contexts. */
    POLYGLOT,
    /** Attempt to access a host API that is not on the explicit allow-list. */
    HOST_ACCESS
}
