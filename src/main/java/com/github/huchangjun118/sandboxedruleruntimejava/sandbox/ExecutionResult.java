package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Outcome of one execution. The exact version executed is always reported so
 * callers can correlate hot replacement/rollback; {@code output} is the
 * bounded captured script log; {@code deterministic} is always true by
 * construction (same input + version + seed => same result).
 */
public record ExecutionResult(
        String ruleId,
        long version,
        long seed,
        Object result,
        String output,
        long elapsedMillis,
        boolean matched,
        boolean deterministic
) {
}
