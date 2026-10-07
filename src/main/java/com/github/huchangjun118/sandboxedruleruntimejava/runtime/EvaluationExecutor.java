package com.github.huchangjun118.sandboxedruleruntimejava.runtime;

import java.util.concurrent.Future;

/**
 * Executes sandboxed evaluations on a bounded, isolated worker pool.
 * A slow script occupies its own worker and can never block fast scripts or the
 * shared runtime; rejecting rather than queueing unbounded work prevents overload.
 */
public interface EvaluationExecutor {
    <T> Future<T> submit(CallableWithContext<T> task);
    void shutdown();
    int activeCount();
    int poolSize();

    /** A task that needs the worker thread's per-execution context. */
    @FunctionalInterface
    interface CallableWithContext<T> {
        T call() throws Exception;
    }
}
