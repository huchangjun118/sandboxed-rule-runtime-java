package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Immutable per-execution quota bundle.
 *
 * <ul>
 *   <li>{@code timeoutMillis}     - wall clock bound for one execution;</li>
 *   <li>{@code memoryBytes}       - tracked allocation bound;</li>
 *   <li>{@code outputBytes}       - bound on captured log + serialized result size;</li>
 *   <li>{@code maxRecursionDepth} - bound on user-call stack depth;</li>
 *   <li>{@code maxSteps}          - bound on interpreted statements/expressions.</li>
 * </ul>
 */
public record ResourceLimits(
        long timeoutMillis,
        long memoryBytes,
        long outputBytes,
        int maxRecursionDepth,
        long maxSteps
) {

    public static final ResourceLimits DEFAULT = new ResourceLimits(
            200L, 64L * 1024L, 8L * 1024L, 64, 2_000_000L);

    public ResourceLimits {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (memoryBytes <= 0) {
            throw new IllegalArgumentException("memoryBytes must be positive");
        }
        if (outputBytes <= 0) {
            throw new IllegalArgumentException("outputBytes must be positive");
        }
        if (maxRecursionDepth <= 0) {
            throw new IllegalArgumentException("maxRecursionDepth must be positive");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }
}
