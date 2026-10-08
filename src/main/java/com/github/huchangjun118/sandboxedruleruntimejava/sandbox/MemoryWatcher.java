package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide enforcement of per-evaluation <b>retained heap</b> budgets.
 *
 * <p>Why retained heap and not cumulative allocation: GraalJS in interpreted-only mode
 * generates large volumes of short-lived allocations even for pure CPU loops (tens of MB
 * per second) that the JVM immediately collects. Bounding cumulative allocation would
 * reject harmless CPU-bound scripts; measuring post-GC live-heap growth instead captures
 * what a script actually keeps (arrays/objects/strings) — the resource a runaway script
 * uses to exhaust the host.</p>
 *
 * <p>A single daemon scheduler performs at most one full-heap measurement per tick for
 * all active evaluations, so concurrent scripts do not each trigger their own GC and
 * contend the runtime. When an evaluation exceeds its budget it is marked and its
 * force-cancel action (closing the polyglot context) is invoked once.</p>
 */
public final class MemoryWatcher {

    /** One active evaluation's accounting. */
    private static final class Account {
        final long baseline;
        final long limit;
        final Runnable forceCancel;
        final AtomicBoolean exceeded = new AtomicBoolean(false);

        Account(long baseline, long limit, Runnable forceCancel) {
            this.baseline = baseline;
            this.limit = limit;
            this.forceCancel = forceCancel;
        }
    }

    private static final Set<Account> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final long POLL_MILLIS = 250;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rule-mem-watch");
                t.setDaemon(true);
                return t;
            });

    static {
        SCHEDULER.scheduleAtFixedRate(MemoryWatcher::tick,
                POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void tick() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        long live;
        try {
            Runtime runtime = Runtime.getRuntime();
            runtime.gc();
            live = runtime.totalMemory() - runtime.freeMemory();
        } catch (Throwable t) {
            return;
        }
        for (Account account : ACTIVE) {
            if (live - account.baseline > account.limit
                    && account.exceeded.compareAndSet(false, true)
                    && account.forceCancel != null) {
                account.forceCancel.run();
            }
        }
    }

    /** Process-wide live (post-GC) heap usage. */
    public static long liveHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private final Account account;

    public MemoryWatcher(Thread worker, long baselineIgnored, long limitBytes,
                         long pollIntervalMillis, Runnable forceCancel) {
        this.account = new Account(liveHeapBytes(), limitBytes, forceCancel);
    }

    public boolean isExceeded() {
        return account.exceeded.get();
    }

    public void start() {
        ACTIVE.add(account);
    }

    public void stop() {
        ACTIVE.remove(account);
    }
}
