package com.github.huchangjun118.sandboxedruleruntimejava.runtime;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed-size pool of sandbox worker threads.
 *
 * <p>Properties relevant to the requirements:</p>
 * <ul>
 *   <li><b>Isolation / head-of-line blocking:</b> each evaluation runs on its own pooled
 *       thread; a script stuck in a tight loop occupies only that one worker, so other
 *       workers keep serving fast scripts.</li>
 *   <li><b>Back-pressure:</b> the queue is small. When every worker and the queue are full
 *       the task is rejected (not buffered without bound), preventing a flood of slow
 *       scripts from exhausting host memory.</li>
 *   <li><b>Bounded stack:</b> worker threads are created with a reduced stack size as a
 *       defence-in-depth backstop for runaway recursion.</li>
 * </ul>
 */
public final class BoundedSandboxExecutor implements EvaluationExecutor {

    private final ThreadPoolExecutor pool;
    private final int poolSize;

    public BoundedSandboxExecutor(int poolSize, int queueCapacity, long workerStackBytes) {
        this.poolSize = Math.max(1, poolSize);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(null, r,
                    "rule-sandbox-" + counter.incrementAndGet(), workerStackBytes);
            t.setDaemon(true);
            return t;
        };
        this.pool = new ThreadPoolExecutor(
                this.poolSize, this.poolSize,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
                factory,
                (r, executor) -> {
                    throw new RejectedExecutionException(
                            "sandbox saturated: all workers busy and queue full");
                });
        // Start workers eagerly so their existence does not add latency to the first
        // request; the runtime performs Graal-level per-worker warm-up after construction.
        this.pool.prestartAllCoreThreads();
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(CallableWithContext<T> task) {
        return pool.submit(task::call);
    }

    @Override
    public void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return pool.awaitTermination(timeout, unit);
    }

    @Override
    public int activeCount() {
        return pool.getActiveCount();
    }

    @Override
    public int poolSize() {
        return poolSize;
    }
}
