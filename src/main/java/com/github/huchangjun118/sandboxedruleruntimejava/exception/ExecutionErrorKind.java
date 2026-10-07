package com.github.huchangjun118.sandboxedruleruntimejava.exception;

/**
 * Categorises why a single script execution was aborted or failed.
 */
public enum ExecutionErrorKind {
    /** Script source could not be parsed/compiled. */
    COMPILE_ERROR,
    /** The script threw an unhandled guest exception. */
    SCRIPT_ERROR,
    /** Wall-clock time limit exceeded. */
    TIMEOUT,
    /** Allocated-bytes quota exceeded. */
    MEMORY_LIMIT,
    /** Printed output exceeded the output-size quota. */
    OUTPUT_LIMIT,
    /** Call-depth / recursion limit exceeded. */
    RECURSION_LIMIT,
    /** Statement/instruction budget exceeded (e.g. tight infinite loop). */
    STATEMENT_LIMIT,
    /** A sandbox capability access was denied. */
    DENIED,
    /** Execution was rejected because the runtime is shutting down or overloaded. */
    REJECTED,
    /** Anything else (unexpected host failure). */
    INTERNAL
}
