package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimitException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimits;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-execution mutable state: deterministic RNG, quota accounting (time,
 * memory, output, steps, recursion depth) and captured output.
 *
 * <p>A fresh context is allocated for every execution and nothing here is
 * shared across executions, so scripts can never observe or corrupt another
 * execution's state. When a quota is exceeded the corresponding
 * {@link ResourceLimitException} unwinds the whole evaluation; the worker
 * thread itself stays alive and is immediately reusable, i.e. execution rolls
 * back to a clean state.</p>
 *
 * <p>Time checks are amortized (every {@link #TIME_CHECK_PERIOD} steps) so
 * tight loops stay fast while still being interrupted promptly.</p>
 */
public final class RuntimeContext {

    private static final int TIME_CHECK_PERIOD = 1024;

    private final ResourceLimits limits;
    private final long seed;
    private final long startNanos;
    private final StringBuilder output;
    private final Deque<String> callStack;

    private long steps;
    private long memoryUsed;

    /** Deterministic xorshift64* state; no system entropy is ever consulted. */
    private long rngState;

    public RuntimeContext(ResourceLimits limits, long seed) {
        this.limits = limits;
        this.seed = seed;
        this.startNanos = System.nanoTime();
        this.output = new StringBuilder();
        this.callStack = new ArrayDeque<>();
        this.rngState = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
    }

    public long seed() {
        return seed;
    }

    public ResourceLimits limits() {
        return limits;
    }

    /** Charge one interpreted step; enforces step/time quotas. */
    public void tick() {
        steps++;
        if (steps > limits.maxSteps()) {
            throw new ResourceLimitException(ResourceLimitException.STEPS,
                    "execution exceeded step limit of " + limits.maxSteps());
        }
        // Cooperate with the outer hard-timeout cancel and with pool shutdown:
        // a pure compute loop must respond to interruption instead of keeping
        // the worker thread (and thus the host process) busy forever.
        if ((steps & 255) == 0 && Thread.currentThread().isInterrupted()) {
            throw new ResourceLimitException(ResourceLimitException.TIME,
                    "execution was interrupted (hard timeout or runtime shutdown)");
        }
        if ((steps & (TIME_CHECK_PERIOD - 1)) == 0) {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            if (elapsedMillis > limits.timeoutMillis()) {
                throw new ResourceLimitException(ResourceLimitException.TIME,
                        "execution exceeded time limit of " + limits.timeoutMillis() + "ms");
            }
        }
    }

    /** Charge tracked allocation; enforces memory quota. */
    public void chargeMemory(long bytes) {
        if (bytes <= 0) {
            return;
        }
        memoryUsed += bytes;
        if (memoryUsed > limits.memoryBytes()) {
            throw new ResourceLimitException(ResourceLimitException.MEMORY,
                    "execution exceeded memory limit of " + limits.memoryBytes() + " bytes"
                            + " (used " + memoryUsed + ")");
        }
    }

    /** Append captured script output; enforces output quota. */
    public void appendOutput(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        long projected = (long) output.length() + chunk.length();
        if (projected > limits.outputBytes()) {
            throw new ResourceLimitException(ResourceLimitException.OUTPUT,
                    "execution exceeded output limit of " + limits.outputBytes() + " bytes");
        }
        output.append(chunk);
    }

    public String output() {
        return output.toString();
    }

    public void enterFunction(String name) {
        if (callStack.size() >= limits.maxRecursionDepth()) {
            throw new ResourceLimitException(ResourceLimitException.RECURSION,
                    "recursion depth exceeded limit of " + limits.maxRecursionDepth()
                            + " at function '" + name + "'");
        }
        callStack.addLast(name);
    }

    public void leaveFunction() {
        if (!callStack.isEmpty()) {
            callStack.removeLast();
        }
    }

    public int currentDepth() {
        return callStack.size();
    }

    /** Deterministic double in [0,1) from the per-execution seed. */
    public double nextRandom() {
        return (nextRandomLong() >>> 11) * 0x1.0p-53;
    }

    /** Deterministic non-negative integer below {@code bound}. */
    public int nextRandomInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return (int) (nextRandomLong() >>> 1) % bound;
    }

    private long nextRandomLong() {
        long x = rngState;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rngState = x;
        return x * 0x2545F4914F6CDD1DL;
    }
}
