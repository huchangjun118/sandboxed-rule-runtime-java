package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls a worker thread's allocated-byte counter from a watchdog thread and trips a flag
 * when the per-execution memory quota is exceeded. The worker checks this flag at
 * instrumentation sampling points and at the end, and the watchdog also interrupts the
 * worker as a backstop. Allocation counts are cumulative for the thread, so the baseline
 * taken at evaluation start is subtracted.
 */
public final class MemoryWatcher {

    private static final ThreadMXBean THREAD_BEAN =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    private final long limitBytes;
    private final Thread worker;
    private final long baseline;
    private final AtomicBoolean exceeded = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final long pollIntervalMillis;
    private final Runnable forceCancel;
    private Thread watcher;

    public MemoryWatcher(Thread worker, long baseline, long limitBytes, long pollIntervalMillis) {
        this(worker, baseline, limitBytes, pollIntervalMillis, null);
    }

    public MemoryWatcher(Thread worker, long baseline, long limitBytes,
                         long pollIntervalMillis, Runnable forceCancel) {
        this.worker = worker;
        this.baseline = baseline;
        this.limitBytes = limitBytes;
        this.pollIntervalMillis = Math.max(1, pollIntervalMillis);
        this.forceCancel = forceCancel;
    }

    public static long allocatedByCurrentThread() {
        return THREAD_BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    public boolean isExceeded() {
        return exceeded.get();
    }

    public void start() {
        watcher = new Thread(this::watchLoop, "rule-mem-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watchLoop() {
        long workerId = worker.getId();
        while (!stopped.get()) {
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                return;
            }
            if (stopped.get()) {
                return;
            }
            long allocated = THREAD_BEAN.getThreadAllocatedBytes(workerId) - baseline;
            if (allocated > limitBytes) {
                exceeded.set(true);
                if (forceCancel != null) {
                    forceCancel.run();
                }
                return;
            }
        }
    }

    public void stop() {
        stopped.set(true);
        if (watcher != null) {
            watcher.interrupt();
        }
        // Clear any interrupt this watcher raised so the pooled worker stays clean.
        Thread.interrupted();
    }
}
