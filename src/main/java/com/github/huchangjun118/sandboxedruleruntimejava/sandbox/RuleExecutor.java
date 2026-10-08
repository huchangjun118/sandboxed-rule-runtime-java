package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script.Interpreter;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script.RuntimeContext;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script.Value;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs prepared rule scripts on a bounded, isolated worker pool.
 *
 * <p>Isolation/cleanliness guarantees:</p>
 * <ul>
 *   <li>each execution gets a fresh input snapshot ({@link Value#ofJava}), a
 *       fresh {@link RuntimeContext} and fresh interpreter environments - no
 *       mutable state is shared between executions, so nothing can leak and
 *       there is nothing to reset afterwards;</li>
 *   <li>scripts run on daemon worker threads; the interpreter cooperatively
 *       checks time/step quotas every statement and loop iteration, and an
 *       outer {@link Future#get} deadline cancels anything that does not yield
 *       in time - dead loops are interrupted, never killing the host JVM;</li>
 *   <li>the pool uses a hand-off queue ({@link java.util.concurrent.SynchronousQueue}),
 *       so a fast script is never queued behind slow ones; once all workers
 *       are busy the caller receives {@link OverloadException} instead of
 *       being blocked;</li>
 *   <li>script failures (compile already excluded) surface as
 *       {@link RuleException}s and never kill the worker thread.</li>
 * </ul>
 */
public final class RuleExecutor implements AutoCloseable {

    /** Extra grace on top of the interpreter's own wall-clock budget. */
    private static final long OUTER_TIMEOUT_GRACE_MS = 100L;

    private final ExecutorService pool;
    private final ResourceLimits defaultLimits;
    private final Interpreter interpreter = new Interpreter();
    private final AtomicInteger threadNumber = new AtomicInteger();

    public RuleExecutor(int workerThreads, ResourceLimits defaultLimits) {
        if (workerThreads <= 0) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        this.defaultLimits = defaultLimits;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "rule-sandbox-worker-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> {
                // Should never happen: every task captures its own throwables.
                System.err.println("[" + thread.getName() + "] uncaught: " + ex);
            });
            return t;
        };
        this.pool = new java.util.concurrent.ThreadPoolExecutor(
                0, workerThreads, 30L, TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                factory, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    public ExecutionResult execute(ScriptVersion version, Object rawInput,
                                   ResourceLimits limits, Long seed) {
        ResourceLimits effectiveLimits = limits != null ? limits : defaultLimits;
        Value input = toValue(rawInput);
        long effectiveSeed = seed != null ? seed
                : deterministicSeed(version.ruleId(), version.version(), input);

        final long startedNanos = System.nanoTime();
        Future<Interpreter.EvalOutcome> future;
        try {
            future = pool.submit(() -> {
                // A reused worker may carry a leftover interrupt flag from a
                // previously cancelled execution; clear it so this run starts clean.
                Thread.interrupted();
                RuntimeContext context = new RuntimeContext(effectiveLimits, effectiveSeed);
                context.chargeMemory(input.estimatedBytes());
                return interpreter.evaluate(version.program(), input, context);
            });
        } catch (RejectedExecutionException e) {
            throw new OverloadException(
                    "sandbox worker pool is saturated; rejecting execution of rule '"
                            + version.ruleId() + "' to avoid blocking fast scripts");
        }

        Interpreter.EvalOutcome outcome;
        try {
            outcome = future.get(effectiveLimits.timeoutMillis() + OUTER_TIMEOUT_GRACE_MS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ResourceLimitException(ResourceLimitException.TIME,
                    "execution of rule '" + version.ruleId()
                            + "' exceeded hard wall clock and was interrupted");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ScriptRuntimeException("execution interrupted before completion", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw unwrap(e.getCause());
        }

        long elapsedMillis = Math.max(1L, (System.nanoTime() - startedNanos) / 1_000_000L);
        Object plain = outcome.result().toPlainJava();
        boolean matched = plain instanceof Boolean b && b;
        return new ExecutionResult(version.ruleId(), version.version(), effectiveSeed,
                plain, outcome.output(), elapsedMillis, matched, true);
    }

    private static RuleException unwrap(Throwable cause) {
        if (cause instanceof RuleException re) {
            return re;
        }
        if (cause instanceof StackOverflowError soe) {
            return new ResourceLimitException(ResourceLimitException.RECURSION,
                    "call stack exhausted the host stack");
        }
        if (cause instanceof Error err) {
            // Defensive: a serious VM error inside the task must not be swallowed silently.
            throw err;
        }
        return new ScriptRuntimeException(
                "unexpected failure during script execution: "
                        + (cause == null ? "unknown" : cause.toString()),
                cause instanceof Exception ex ? ex : null);
    }

    static Value toValue(Object raw) {
        return Value.ofJava(raw);
    }

    /**
     * Seed used when the caller does not provide one: a deterministic mix of
     * rule id, executed version and the exact input graph, so identical input
     * + version still yields bit-for-bit reproducible randomness.
     */
    static long deterministicSeed(String ruleId, long version, Value input) {
        long h = 0x9E3779B97F4A7C15L;
        h = mix(h, ruleId.hashCode());
        h = mix(h, (int) (version ^ (version >>> 32)));
        h = mix(h, hashValue(input));
        return h;
    }

    private static long mix(long h, int k) {
        h ^= k;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        return h;
    }

    private static int hashValue(Value v) {
        if (v instanceof Value.NullVal) {
            return 0;
        }
        if (v instanceof Value.BoolVal b) {
            return b.b() ? 1231 : 1237;
        }
        if (v instanceof Value.NumVal n) {
            return Double.hashCode(n.n());
        }
        if (v instanceof Value.StrVal s) {
            return s.s().hashCode();
        }
        if (v instanceof Value.ArrVal a) {
            int h = 1;
            for (Value item : a.items()) {
                h = 31 * h + hashValue(item);
            }
            return h;
        }
        if (v instanceof Value.ObjVal o) {
            int h = 7;
            for (Map.Entry<String, Value> e : o.fields().entrySet()) {
                h = 31 * h + e.getKey().hashCode();
                h = 31 * h + hashValue(e.getValue());
            }
            return h;
        }
        return 0;
    }

    @Override
    public void close() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(2L, TimeUnit.SECONDS)) {
                System.err.println("rule executor pool did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
