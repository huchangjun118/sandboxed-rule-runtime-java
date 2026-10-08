package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ExecutionResult;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleExecutionService;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core judgment path + reproducibility (requirement 6):
 * same input + same version + same seed must yield identical results,
 * while different seeds are allowed to differ.
 */
class ScriptExecutionTest {

    private RuleExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RuleExecutionService(4, ResourceLimits.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void evaluatesBooleanJudgmentFromRequest() {
        service.publish("risk", """
                var score = input.score;
                var vip = input.profile.vip;
                return score > 80 && !vip;
                """);

        ExecutionResult hit = service.execute("risk",
                Map.of("score", 95, "profile", Map.of("vip", false)));
        ExecutionResult miss = service.execute("risk",
                Map.of("score", 10, "profile", Map.of("vip", false)));
        ExecutionResult vip = service.execute("risk",
                Map.of("score", 99, "profile", Map.of("vip", true)));

        assertEquals(Boolean.TRUE, hit.result());
        assertTrue(hit.matched());
        assertEquals(Boolean.FALSE, miss.result());
        assertEquals(Boolean.FALSE, vip.result());
        assertEquals(1L, hit.version());
        assertTrue(hit.deterministic());
    }

    @Test
    void supportsFunctionsLoopsAndCollections() {
        service.publish("sum", """
                function total(items) {
                    var acc = 0;
                    for (x in items) {
                        acc = acc + x;
                    }
                    return acc;
                }
                return total(input.values) >= 10;
                """);

        ExecutionResult r = service.execute("sum", Map.of("values", new int[]{1, 2, 3, 4}));
        assertEquals(Boolean.TRUE, r.result());
    }

    @Test
    void sameInputVersionAndSeedProduceIdenticalResults() {
        service.publish("draw", """
                var pick = randInt(1000000);
                log pick;
                return pick;
                """);
        Object input = Map.of("round", 7, "tags", new String[]{"a", "b"});

        ExecutionResult r1 = service.execute("draw", input, 12345L);
        ExecutionResult r2 = service.execute("draw", input, 12345L);
        ExecutionResult r3 = service.execute("draw", input, 12345L);

        assertEquals(r1.result(), r2.result());
        assertEquals(r2.result(), r3.result());
        assertEquals(r1.output(), r2.output());
        assertEquals(r1.seed(), r2.seed());
        assertNotNull(r1.result());
    }

    @Test
    void defaultSeedIsStableForSameInputAndVersion() {
        service.publish("draw", "return randInt(1000000);");
        Object input = Map.of("k", "v");

        ExecutionResult r1 = service.execute("draw", input, null);
        ExecutionResult r2 = service.execute("draw", input, null);

        assertEquals(r1.result(), r2.result());
        assertEquals(r1.seed(), r2.seed());
    }

    @Test
    void capturedLogOutputIsReturnedAndBounded() {
        service.publish("echo", """
                log input.name;
                return true;
                """);
        ExecutionResult r = service.execute("echo", Map.of("name", "alice"));
        assertEquals("alice", r.output());
        assertEquals(Boolean.TRUE, r.result());
    }
}
