package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ExecutionResult;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.OverloadException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimitException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimits;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleExecutionService;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement 5: concurrent script executions are isolated. A slow, faulty
 * or resource-exhausting script must not slow down, fail or corrupt other
 * scripts or the shared runtime; fast scripts are never queued behind slow
 * ones past the configured pool.
 */
class ConcurrencyIsolationTest {

    private RuleExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RuleExecutionService(8, ResourceLimits.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private ResourceLimits longTimeout() {
        return new ResourceLimits(3000L, 1024L * 1024L, 4096L, 16, 1_000_000_000L);
    }

    @Test
    void slowScriptsDoNotBlockFastScripts() throws Exception {
        service.publish("slow", """
                var i = 0;
                while (i < 30000000) {
                    i = i + 1;
                }
                return "slow";
                """);
        service.publish("fast", "return \"fast\";");

        // Occupy 4 workers with slow scripts.
        ExecutorService drivers = Executors.newFixedThreadPool(8);
        CountDownLatch slowStarted = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            drivers.submit(() -> {
                slowStarted.countDown();
                service.execute("slow", null, longTimeout(), null);
            });
        }
        slowStarted.await();
        Thread.sleep(40L);

        // Fast scripts on the remaining free workers must finish immediately,
        // not wait behind the busy (slow) ones.
        long start = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            ExecutionResult r = service.execute("fast", null);
            assertEquals("fast", r.result());
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 1_500L,
                "fast scripts were blocked behind slow ones for " + elapsed + "ms");
        drivers.shutdownNow();
        drivers.awaitTermination(5L, TimeUnit.SECONDS);
    }

    @Test
    void failingScriptsDoNotAffectHealthyOnes() {
        service.publish("boom", "return undefinedVar.field;");
        service.publish("div0", "return 1 / 0;");
        service.publish("ok", "return input.x + 1;");

        for (int i = 0; i < 20; i++) {
            assertThrows(ScriptRuntimeException.class,
                    () -> service.execute("boom", null));
            assertThrows(ScriptRuntimeException.class,
                    () -> service.execute("div0", null));
            ExecutionResult r = service.execute("ok", java.util.Map.of("x", 41));
            assertEquals(42L, ((Number) r.result()).longValue());
        }
    }

    @Test
    void timedOutAndLimitedScriptsDoNotAffectOthers() {
        ResourceLimits tight = new ResourceLimits(60L, 1024L * 1024L, 4096L, 16, 100_000_000L);
        service.publish("hang", "while (true) { }");
        service.publish("ok", "return true;");

        for (int i = 0; i < 6; i++) {
            ResourceLimitException ex = assertThrows(ResourceLimitException.class,
                    () -> service.execute("hang", null, tight, null));
            assertEquals(ResourceLimitException.TIME, ex.reasonCode());
            assertEquals(Boolean.TRUE, service.execute("ok", null).result());
        }
    }

    @Test
    void manyMixedConcurrentScriptsStayIsolated() throws Exception {
        service.publish("hang", "while (true) { }");
        service.publish("boom", "return missing.value;");
        service.publish("ok", "return input.n * 2;");

        ResourceLimits tight = tightLimits();
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger oks = new AtomicInteger();
        AtomicInteger expectedFailures = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        try {
                            switch (idx % 3) {
                                case 0 -> service.execute("hang", null, tight, null);
                                case 1 -> service.execute("boom", null);
                                default -> {
                                    ExecutionResult r = service.execute(
                                            "ok", java.util.Map.of("n", idx), null);
                                    if (((Number) r.result()).longValue() == idx * 2L) {
                                        oks.incrementAndGet();
                                    }
                                }
                            }
                        } catch (ResourceLimitException | ScriptRuntimeException
                                | OverloadException expected) {
                            // Resource exhaustion of one script, its own runtime
                            // fault, or pool saturation rejecting overflow - all
                            // are isolated, well-typed outcomes, never cross damage.
                            expectedFailures.incrementAndGet();
                        }
                    }
                } catch (Throwable any) {
                    unexpected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30L, TimeUnit.SECONDS));
        assertEquals(0, unexpected.get(), "no unexpected cross-script failures");
        assertTrue(oks.get() > 0, "healthy scripts kept producing results");
        assertTrue(expectedFailures.get() > 0, "bad scripts kept failing in isolation");
    }

    @Test
    void saturatedPoolRejectsImmediatelyInsteadOfBlocking() throws Exception {
        // Single worker pool: occupy the only worker, next call must be rejected.
        RuleExecutionService tiny = new RuleExecutionService(1, ResourceLimits.DEFAULT);
        try {
            tiny.publish("slow", """
                    var i = 0;
                    while (i < 20000000) {
                        i = i + 1;
                    }
                    return 1;
                    """);
            tiny.publish("other", "return 2;");
            ExecutorService drivers = Executors.newSingleThreadExecutor();
            try {
                drivers.submit(() -> tiny.execute("slow", null, longTimeout(), null));
                Thread.sleep(60L);
                assertThrows(OverloadException.class,
                        () -> tiny.execute("other", null));
            } finally {
                drivers.shutdownNow();
                drivers.awaitTermination(5L, TimeUnit.SECONDS);
            }
        } finally {
            tiny.close();
        }
    }

    private static ResourceLimits tightLimits() {
        return new ResourceLimits(50L, 1024L * 1024L, 4096L, 16, 50_000_000L);
    }
}
