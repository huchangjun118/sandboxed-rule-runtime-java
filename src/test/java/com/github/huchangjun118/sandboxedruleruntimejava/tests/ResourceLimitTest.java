package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ExecutionResult;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimitException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimits;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleExecutionService;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement 3: every single execution is bounded by time, memory, output
 * size, recursion depth and step count; exceeding a bound interrupts the
 * execution and leaves the runtime clean for subsequent calls.
 */
class ResourceLimitTest {

    private RuleExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RuleExecutionService(8, ResourceLimits.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private ResourceLimits tight() {
        // Generous step/memory so the wall clock is the binding constraint.
        return new ResourceLimits(80L, 1024L * 1024L, 4096L, 16, 100_000_000L);
    }

    @Test
    void infiniteLoopIsInterruptedByTimeLimit() {
        service.publish("hang", "while (true) { }");
        long start = System.currentTimeMillis();
        ResourceLimitException ex = assertThrows(ResourceLimitException.class,
                () -> service.execute("hang", null, tight(), null));
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(ResourceLimitException.TIME, ex.reasonCode());
        assertTrue(elapsed < 2_000L, "time limit not enforced promptly, took " + elapsed + "ms");
    }

    @Test
    void heavyIterationIsInterruptedByTimeLimit() {
        service.publish("busy", """
                var x = 0;
                while (x < 9000000000) {
                    x = x + 1;
                }
                return x;
                """);
        ResourceLimitException ex = assertThrows(ResourceLimitException.class,
                () -> service.execute("busy", null, tight(), null));
        assertEquals(ResourceLimitException.TIME, ex.reasonCode());
    }

    @Test
    void memoryQuotaStopsUnboundedAllocation() {
        service.publish("grow", """
                var a = [];
                var i = 0;
                while (i < 100000) {
                    a = a + [\"012345678901234567890123456789\"];
                    i = i + 1;
                }
                return len(a);
                """);
        ResourceLimits limits = new ResourceLimits(500L, 2048L, 4096L, 16, 100_000_000L);
        ResourceLimitException ex = assertThrows(ResourceLimitException.class,
                () -> service.execute("grow", null, limits, null));
        assertEquals(ResourceLimitException.MEMORY, ex.reasonCode());
    }

    @Test
    void outputQuotaStopsLogSpam() {
        service.publish("spam", """
                var i = 0;
                while (i < 100000) {
                    log \"01234567890123456789\";
                    i = i + 1;
                }
                return true;
                """);
        ResourceLimits limits = new ResourceLimits(500L, 1024L * 1024L, 128L, 16, 100_000_000L);
        ResourceLimitException ex = assertThrows(ResourceLimitException.class,
                () -> service.execute("spam", null, limits, null));
        assertEquals(ResourceLimitException.OUTPUT, ex.reasonCode());
    }

    @Test
    void recursionQuotaStopsDeepCalls() {
        service.publish("deep", """
                function f(n) {
                    return f(n + 1);
                }
                return f(0);
                """);
        ResourceLimits limits = new ResourceLimits(500L, 1024L * 1024L, 4096L, 20, 100_000_000L);
        ResourceLimitException ex = assertThrows(ResourceLimitException.class,
                () -> service.execute("deep", null, limits, null));
        assertEquals(ResourceLimitException.RECURSION, ex.reasonCode());
    }

    @Test
    void stepQuotaStopsStatementFlood() {
        service.publish("steps", """
                var i = 0;
                while (i >= 0) {
                    i = i + 1;
                }
                return i;
                """);
        ResourceLimits limits = new ResourceLimits(60_000L, 1024L * 1024L, 4096L, 16, 5_000L);
        ResourceLimitException ex = assertThrows(ResourceLimitException.class,
                () -> service.execute("steps", null, limits, null));
        assertEquals(ResourceLimitException.STEPS, ex.reasonCode());
    }

    @Test
    void runtimeIsCleanAndReusableAfterLimitFailure() {
        service.publish("hang", "while (true) { }");
        service.publish("ok", "return input.n * 2;");

        for (int i = 0; i < 3; i++) {
            assertThrows(ResourceLimitException.class,
                    () -> service.execute("hang", null, tight(), null));
            ExecutionResult r = service.execute("ok", java.util.Map.of("n", 21), 1L);
            assertEquals(42L, ((Number) r.result()).longValue());
        }
        // The host process is obviously still alive (we are asserting here).
        assertTrue(true);
    }

    @Test
    void missingRuleIsReportedSeparately() {
        assertThrows(RuleNotFoundException.class,
                () -> service.execute("never-published", null));
    }
}
