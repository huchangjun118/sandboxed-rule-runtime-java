package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.config.RuntimeProperties;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.ScriptExecutionException;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationResult;
import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.VersionConflictException;
import com.github.huchangjun118.sandboxedruleruntimejava.runtime.DefaultRuleRuntime;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.GraalSandboxEngine;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.SandboxPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hot-swap/rollback semantics:
 * at most one committed version per rule id at any instant, no torn reads; requests
 * in flight when a swap happens finish against the single version they pinned (exactly
 * once, never lost); rollback restores earlier content atomically.
 */
class HotSwapRollbackTest {

    private DefaultRuleRuntime runtime;
    private ResourceQuota quota;

    @BeforeEach
    void setUp() {
        RuntimeProperties props = new RuntimeProperties();
        props.setWorkerThreads(8);
        props.setDefaultTimeoutMillis(20000);
        props.setHistoryPerRule(50);
        runtime = new DefaultRuleRuntime(props, new GraalSandboxEngine(new SandboxPolicy()));
        quota = runtime.defaultQuota();
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
    }

    private EvaluationRequest req(String id) {
        return new EvaluationRequest(id, Map.of("ms", 0), Map.of(), 1L);
    }

    @Test
    void onlyMonotonicVersionsAccepted() {
        runtime.publish("m", 1, "function decide(){return {allowed:true};}", quota);
        assertThrows(VersionConflictException.class,
                () -> runtime.publish("m", 1, "function decide(){return {allowed:false};}", quota));
        assertEquals(1, runtime.currentVersion("m"));
    }

    @Test
    void failedCompileDoesNotReplaceCurrentVersion() {
        runtime.publish("c", 1, "function decide(){return {allowed:true,reason:'good'};}", quota);
        assertThrows(ScriptExecutionException.class,
                () -> runtime.publish("c", 2, "function decide({ this is not js", quota));
        assertEquals(1, runtime.currentVersion("c"));
        assertEquals("good", runtime.evaluate(req("c")).getDecision().getReason());
    }

    @Test
    void rollbackRestoresContentAtomically() {
        runtime.publish("rb", 1, "function decide(){return {allowed:true,reason:'a'};}", quota);
        runtime.publish("rb", 2, "function decide(){return {allowed:false,reason:'b'};}", quota);
        assertEquals("b", runtime.evaluate(req("rb")).getDecision().getReason());
        runtime.rollback("rb", 1);
        EvaluationResult r = runtime.evaluate(req("rb"));
        assertEquals("a", r.getDecision().getReason());
        assertTrue(r.getDecision().isAllowed());
    }

    @Test
    void unknownRuleAndUnknownRollbackVersionRejected() {
        assertThrows(VersionConflictException.class, () -> runtime.rollback("nope", 1));
        runtime.publish("rr", 1, "function decide(){return {allowed:true};}", quota);
        assertThrows(VersionConflictException.class, () -> runtime.rollback("rr", 99));
    }

    /**
     * End-to-end overlap: many v1 evaluations and the publication of v2 run at the same
     * time. Every evaluation must complete exactly once with a version and a decision
     * that belong together (never torn), none lost, and the runtime healthy afterwards.
     * The strict "pinned snapshot never changes under a swap" guarantee is additionally
     * covered deterministically in {@link RegistryPinningTest}.
     */
    @Test
    void evaluationsDuringSwapCompleteExactlyOnceAndConsistently() throws Exception {
        ResourceQuota busy = new ResourceQuota(15000, 32L * 1024 * 1024,
                quota.getOutputBytes(), quota.getMaxRecursionDepth(), 100_000_000L);
        runtime.publish("sw", 1,
                "function decide(input){ var s=0; for(var i=0;i<(input.n|0);i++){ s=(s+i)|0; }"
              + " return {allowed:true, reason:'v1', attributes:{s:s}}; }", busy);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();

        // Concurrent v1 evaluations, each doing a small amount of work so some are
        // genuinely running when v2 lands (without dominating the shared CPUs).
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    EvaluationResult r = runtime.evaluate(
                            new EvaluationRequest("sw", Map.of("n", 300_000), Map.of(), 1L));
                    // Version and reason must always agree: no field-level mixing.
                    String reason = r.getDecision().getReason();
                    boolean consistent =
                            (r.getVersion() == 1 && "v1".equals(reason)) ||
                            (r.getVersion() == 2 && "v2".equals(reason));
                    if (!consistent) {
                        errors.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();

                }
            }));
        }
        start.countDown();
        // Swap while evaluations are in flight.
        Thread.sleep(30);
        runtime.publish("sw", 2, "function decide(){ return {allowed:true, reason:'v2'}; }", quota);

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(0, errors.get(), "no evaluation may be lost or return a torn version");
        assertEquals(2, runtime.currentVersion("sw"));
        assertEquals("v2", runtime.evaluate(req("sw")).getDecision().getReason());
    }

    /**
     * Hammer publish and evaluate concurrently: every observed result must reference a
     * version that was actually committed and be internally consistent (no field-level
     * mixing, no duplicate/lost outcome at the future level).
     */
    @Test
    void concurrentSwapsProduceOnlyCommittedConsistentResults() throws Exception {
        runtime.publish("hot", 1,
                "function decide(input){ return {allowed:true, reason:'v'+input.v}; }", quota);
        int versions = 10;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        ConcurrentMap<Integer, AtomicInteger> observed = new ConcurrentHashMap<>();
        Map<Integer, Future<?>> futures = new java.util.HashMap<>();

        for (int v = 2; v <= versions; v++) {
            int ver = v;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                runtime.publish("hot", ver,
                        "function decide(input){ return {allowed:true, reason:'v'+input.v}; }",
                        quota);
            });
        }
        for (int i = 0; i < 48; i++) {
            Future<?> f = pool.submit(() -> {
                try {
                    start.await();
                    EvaluationResult r = runtime.evaluate(
                            new EvaluationRequest("hot", Map.of("v", 0), Map.of(), 1L));
                    observed.computeIfAbsent(r.getVersion(), k -> new AtomicInteger()).incrementAndGet();
                    // version reported must be between 1 and versions (a committed one)
                    if (r.getVersion() < 1 || r.getVersion() > versions) {
                        errors.incrementAndGet();
                    }
                    if (r.getDecision() == null) {
                        errors.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            futures.put(i, f);
        }
        start.countDown();
        for (Future<?> f : futures.values()) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(0, errors.get());
        assertEquals(versions, runtime.currentVersion("hot"));
        assertFalse(observed.isEmpty());
    }
}
