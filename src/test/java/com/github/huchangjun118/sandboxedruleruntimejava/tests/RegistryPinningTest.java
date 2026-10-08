package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.AtomicVersionRegistry;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.RuleVersion;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.VersionConflictException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic tests for the core versioning contract that underpins hot-swap safety:
 * <ul>
 *   <li>at any instant a rule id resolves to exactly one committed, complete version;</li>
 *   <li>a reader captures a reference once and keeps seeing that exact snapshot even
 *       while newer versions are committed (no torn/field-mixed reads, no lost or
 *       duplicated decision because the executing version is pinned);</li>
 *   <li>old versions remain resolvable from history so in-flight calls can finish.</li>
 * </ul>
 */
class RegistryPinningTest {

    private final ResourceQuota q = new ResourceQuota(1000, 1024, 1024, 100, 1000);

    private AtomicVersionRegistry registry() {
        return new AtomicVersionRegistry((id, v, src) -> "compiled:" + v, 10);
    }

    @Test
    void pinnedSnapshotIsStableAcrossSwaps() {
        AtomicVersionRegistry reg = registry();
        reg.publish("r", 1, "src1", q);
        // Simulate a request capturing the current version exactly once when it starts.
        RuleVersion pinned = reg.current("r").orElseThrow();
        assertEquals(1, pinned.getVersion());
        assertEquals("src1", pinned.getSource());

        // While that request is "in flight" (still holding pinned), many swaps occur.
        for (int v = 2; v <= 10; v++) {
            reg.publish("r", v, "src" + v, q);
        }
        // The in-flight call continues to see its immutable pinned snapshot, never a mix.
        assertEquals(1, pinned.getVersion());
        assertEquals("src1", pinned.getSource());
        assertEquals(q.getTimeoutMillis(), pinned.getQuota().getTimeoutMillis());
        // New arrivals see the latest.
        assertEquals(10, reg.current("r").orElseThrow().getVersion());
        // The old version is still available to finish/roll back to.
        assertTrue(reg.history("r").stream().anyMatch(x -> x.getVersion() == 1));
    }

    @Test
    void concurrentReadsAlwaysSeeACommittedConsistentVersion() throws Exception {
        AtomicVersionRegistry reg = registry();
        reg.publish("r", 1, "src1", q);
        int writers = 1, readers = 6, versions = 200;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread w = new Thread(() -> {
            try {
                start.await();
                for (int v = 2; v <= versions; v++) {
                    reg.publish("r", v, "src" + v, q);
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });
        Thread[] rs = new Thread[readers];
        for (int i = 0; i < readers; i++) {
            rs[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 10_000; k++) {
                        Optional<RuleVersion> cur = reg.current("r");
                        RuleVersion snap = cur.orElseThrow();
                        // Field-level consistency: source and version always agree with a
                        // fully committed entry ("srcN" matches version N).
                        assertEquals("src" + snap.getVersion(), snap.getSource(),
                                "torn read: version/fields mismatched");
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            });
        }
        w.start();
        for (Thread t : rs) {
            t.start();
        }
        start.countDown();
        w.join();
        for (Thread t : rs) {
            t.join();
        }
        assertNull(error.get());
        assertEquals(versions, reg.current("r").orElseThrow().getVersion());
    }

    @Test
    void rollbackTargetRemainsExecutableWhileSwapsContinue() {
        AtomicVersionRegistry reg = registry();
        reg.publish("r", 1, "src1", q);
        RuleVersion old = reg.current("r").orElseThrow();
        reg.publish("r", 2, "src2", q);
        reg.publish("r", 3, "src3", q);
        // An in-flight v1 holder is unaffected; rollback points current back at v1 content.
        RuleVersion restored = reg.rollback("r", 1);
        assertEquals("src1", restored.getSource());
        assertTrue(restored.getVersion() > 3, "rollback appends a new monotonic version");
        assertEquals(restored.getVersion(), reg.current("r").orElseThrow().getVersion());
        // original pinned reference still intact
        assertEquals(1, old.getVersion());
    }

    @Test
    void publishRejectsNonMonotonicAndKeepsSnapshot() {
        AtomicVersionRegistry reg = registry();
        reg.publish("r", 5, "src5", q);
        assertThrows(VersionConflictException.class, () -> reg.publish("r", 5, "dup", q));
        assertThrows(VersionConflictException.class, () -> reg.publish("r", 4, "old", q));
        assertEquals("src5", reg.current("r").orElseThrow().getSource());
    }
}
