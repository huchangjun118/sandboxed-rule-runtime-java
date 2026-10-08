package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ExecutionResult;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimits;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleExecutionService;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.SandboxViolationException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptCompileException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirements 1 & 4: versioned immutable releases with one committed
 * version at a time, atomic hot replacement, rollback, and in-flight old
 * version calls that finish on their own release.
 */
class VersionManagementTest {

    private RuleExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RuleExecutionService(8, ResourceLimits.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void publishesMonotonicVersionsAndListsHistory() {
        ScriptVersion v1 = service.publish("r", "return 1;");
        ScriptVersion v2 = service.publish("r", "return 2;");
        ScriptVersion v3 = service.publish("r", "return 3;");

        assertEquals(1L, v1.version());
        assertEquals(2L, v2.version());
        assertEquals(3L, v3.version());
        assertEquals(3L, service.current("r").orElseThrow().version());
        assertEquals(List.of(1L, 2L, 3L), service.versions("r"));
    }

    @Test
    void explicitVersionMustStrictlyIncrease() {
        service.publish("r", 5L, "return 1;");
        assertThrows(IllegalArgumentException.class,
                () -> service.publish("r", 5L, "return 2;"));
        assertThrows(IllegalArgumentException.class,
                () -> service.publish("r", 4L, "return 2;"));
        service.publish("r", 6L, "return 2;");
        assertEquals(6L, service.current("r").orElseThrow().version());
    }

    @Test
    void failedPublishNeverRevealsPartialState() {
        service.publish("r", "return 7;");

        // Syntax error: nothing new is committed.
        assertThrows(ScriptCompileException.class,
                () -> service.publish("r", "var = 1;"));
        // Capability violation: nothing new is committed.
        assertThrows(SandboxViolationException.class,
                () -> service.publish("r", "return exec(\"x\");"));

        assertEquals(1L, service.current("r").orElseThrow().version());
        assertEquals(7L, ((Number) service.execute("r", null).result()).longValue());
    }

    @Test
    void rollbackPointsCurrentAtExistingImmutableRelease() {
        service.publish("r", "return 1;");
        service.publish("r", "return 2;");
        service.publish("r", "return 3;");
        assertEquals(3L, service.current("r").orElseThrow().version());

        ScriptVersion back = service.rollback("r", 1L);
        assertEquals(1L, back.version());
        assertEquals(1L, service.current("r").orElseThrow().version());
        assertEquals(1L, ((Number) service.execute("r", null).result()).longValue());

        // History is untouched and forward again works.
        service.publish("r", "return 4;");
        assertEquals(4L, service.current("r").orElseThrow().version());
        assertEquals(List.of(1L, 2L, 3L, 4L), service.versions("r"));
    }

    @Test
    void hotReplacementIsVisibleToNewRequestsImmediately() {
        service.publish("r", "return \"v1\";");
        assertEquals("v1", service.execute("r", null).result());

        service.publish("r", "return \"v2\";");
        assertEquals("v2", service.execute("r", null).result());
    }

    @Test
    void inFlightCallFinishesOnOldVersionAfterHotReplacement() throws Exception {
        ResourceLimits limits = new ResourceLimits(
                5000L, 1024L * 1024L, 4096L, 16, 1000000000L);
        // v1 burns bounded time so the replacement lands while it runs.
        service.publish("slow", """
                var acc = 0;
                var i = 0;
                while (i < 4000000) {
                    acc = acc + i;
                    i = i + 1;
                }
                return "v1-result";
                """);

        CompletableFuture<ExecutionResult> inFlight = CompletableFuture.supplyAsync(
                () -> service.execute("slow", null, limits, 42L));
        Thread.sleep(30L);
        service.publish("slow", "return \"v2-result\";");

        ExecutionResult r = inFlight.join();
        assertEquals("v1-result", r.result());
        assertEquals(1L, r.version(), "in-flight call must finish on its captured version");
        // Subsequent requests observe the replacement.
        assertEquals("v2-result", service.execute("slow", null).result());
        assertEquals(2L, service.current("slow").orElseThrow().version());
    }

    @Test
    void requestsDuringReplacementAreNeitherLostNorDoubleJudged() throws Exception {
        // Dedicated wide pool so concurrent fast requests are never rejected
        // purely by pool saturation (that independent guard is tested elsewhere).
        RuleExecutionService wide = new RuleExecutionService(64, ResourceLimits.DEFAULT);
        try {
            wide.publish("r", "return input.n;");
            runNoLossNoDouble(wide);
        } finally {
            wide.close();
        }
    }

    private void runNoLossNoDouble(RuleExecutionService svc) throws Exception {
        int total = 40;
        List<long[]> pairs = new CopyOnWriteArrayList<>();
        List<Thread> threads = new java.util.ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < total; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try {
                    ExecutionResult r = svc.execute("r", Map.of("n", n), 1L);
                    pairs.add(new long[]{n, ((Number) r.result()).longValue(), r.version()});
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            threads.add(t);
        }
        // Concurrent hot replacement while all requests are in the air.
        Thread replacer = new Thread(() -> svc.publish("r", "return input.n + 1000;"));
        replacer.start();
        for (Thread t : threads) {
            t.start();
        }
        replacer.join();
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(null, failure.get());
        assertEquals(total, pairs.size(), "no request may be lost");
        for (long[] p : pairs) {
            long n = p[0];
            long got = p[1];
            long version = p[2];
            // Exactly one verdict: either the old value or the new value, never both/partial.
            assertTrue(got == n || got == n + 1000,
                    "request judged against mixed/partial state: " + got);
            assertTrue(version == 1L || version == 2L);
        }
    }
}
