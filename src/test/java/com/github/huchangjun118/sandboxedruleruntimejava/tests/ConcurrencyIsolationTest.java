package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.config.RuntimeProperties;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.ScriptExecutionException;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationResult;
import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;
import com.github.huchangjun118.sandboxedruleruntimejava.runtime.DefaultRuleRuntime;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.GraalSandboxEngine;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.SandboxPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent scripts must be mutually isolated: a slow or failing script must not block
 * or corrupt fast scripts or the shared runtime, and globals must not leak between runs.
 */
class ConcurrencyIsolationTest {

    private DefaultRuleRuntime runtime;
    private ResourceQuota quota;

    @BeforeEach
    void setUp() {
        RuntimeProperties props = new RuntimeProperties();
        props.setWorkerThreads(8);
        props.setDefaultTimeoutMillis(10000);
        runtime = new DefaultRuleRuntime(props, new GraalSandboxEngine(new SandboxPolicy()));
        quota = runtime.defaultQuota();
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
    }

    @Test
    void slowScriptsDoNotBlockFastScripts() throws Exception {
        // Several workers get stuck in infinite loops (timeout 1s); meanwhile many fast
        // rules must still complete promptly on other workers.
        ResourceQuota slow = new ResourceQuota(800, quota.getMemoryBytes(),
                quota.getOutputBytes(), quota.getMaxRecursionDepth(), 10_000_000_000L);
        runtime.publish("slow", 1, "function decide(){ while(true){} }", slow);
        runtime.publish("fast", 1,
                "function decide(input){ return {allowed:true, reason:String(input.n)}; }",
                new ResourceQuota(10000, quota.getMemoryBytes(), quota.getOutputBytes(),
                        quota.getMaxRecursionDepth(), quota.getStatementLimit()));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                assertThrows(ScriptExecutionException.class,
                        () -> runtime.evaluate(new EvaluationRequest("slow", Map.of(), Map.of(), 1L)));
            }));
        }
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                EvaluationResult r = runtime.evaluate(new EvaluationRequest(
                        "fast", Map.of("n", n), Map.of(), 1L));
                assertEquals(String.valueOf(n), r.getDecision().getReason());
                assertTrue(r.getDecision().isAllowed());
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        long elapsed = System.currentTimeMillis() - t0;
        // Fast scripts must not wait for the 1s slow scripts to be killed.
        assertTrue(elapsed < 15000, "fast scripts were blocked by slow ones: " + elapsed + "ms");
        pool.shutdownNow();
    }

    @Test
    void failingScriptDoesNotCorruptOthersOrRuntime() throws Exception {
        runtime.publish("boom", 1, "function decide(){ throw new Error('kaboom'); }", quota);
        runtime.publish("ok", 1, "function decide(){ return {allowed:true}; }", quota);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            boolean shouldFail = i % 2 == 0;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (shouldFail) {
                        assertThrows(ScriptExecutionException.class,
                                () -> runtime.evaluate(new EvaluationRequest("boom", Map.of(), Map.of(), 1L)));
                    } else {
                        assertTrue(runtime.evaluate(new EvaluationRequest("ok", Map.of(), Map.of(), 1L))
                                .getDecision().isAllowed());
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertTrue(failures.isEmpty(), "unexpected failures: " + failures);
    }

    @Test
    void globalsDoNotLeakAcrossExecutions() {
        // A mutable global written by one evaluation must not be visible to another,
        // because every evaluation gets a fresh context.
        runtime.publish("leak", 1,
                "var counter = (typeof counter === 'undefined') ? 0 : counter + 1; "
              + "function decide(){ counter = counter + 1; return {allowed: counter === 1, reason:String(counter)}; }",
                quota);
        for (int i = 0; i < 10; i++) {
            EvaluationResult r = runtime.evaluate(
                    new EvaluationRequest("leak", Map.of(), Map.of(), 1L));
            assertEquals("1", r.getDecision().getReason(),
                    "global state leaked across isolated contexts at iteration " + i);
        }
    }
}
