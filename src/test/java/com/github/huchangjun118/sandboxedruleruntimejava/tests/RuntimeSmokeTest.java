package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.config.RuntimeProperties;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.ExecutionErrorKind;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSmokeTest {

    private DefaultRuleRuntime runtime;
    private ResourceQuota quota;

    @BeforeEach
    void setUp() {
        RuntimeProperties props = new RuntimeProperties();
        props.setWorkerThreads(4);
        props.setDefaultTimeoutMillis(1000);
        props.setDefaultMemoryBytes(16L * 1024 * 1024);
        props.setDefaultOutputBytes(16 * 1024);
        props.setDefaultRecursionDepth(200);
        props.setDefaultStatementLimit(2_000_000);
        runtime = new DefaultRuleRuntime(props, new GraalSandboxEngine(new SandboxPolicy()));
        quota = runtime.defaultQuota();
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
    }

    private EvaluationRequest req(String id, long seed) {
        return new EvaluationRequest(id, Map.of("amount", 150), Map.of(), seed);
    }

    @Test
    void basicDecision() {
        runtime.publish("r1", 1,
                "function decide(input, ctx){ return {allowed: input.amount < 100, reason: 'limit'}; }",
                quota);
        EvaluationResult result = runtime.evaluate(req("r1", 1L));
        assertEquals(1, result.getVersion());
        assertFalse(result.getDecision().isAllowed());
        assertEquals("limit", result.getDecision().getReason());
    }

    @Test
    void denyFileAccess() {
        runtime.publish("r2", 1,
                "function decide(input, ctx){ var F=Java.type('java.io.File'); return {allowed:false}; }",
                quota);
        ScriptExecutionException e = assertThrows(ScriptExecutionException.class,
                () -> runtime.evaluate(req("r2", 1L)));
        assertEquals(ExecutionErrorKind.DENIED, e.getKind());
        assertNotNull(e.getDenyReason());
    }

    @Test
    void timeoutInfiniteLoop() {
        ResourceQuota fast = new ResourceQuota(200, quota.getMemoryBytes(),
                quota.getOutputBytes(), quota.getMaxRecursionDepth(), 10_000_000_000L);
        runtime.publish("r3", 1, "function decide(){ while(true){} }", fast);
        ScriptExecutionException e = assertThrows(ScriptExecutionException.class,
                () -> runtime.evaluate(req("r3", 1L)));
        assertEquals(ExecutionErrorKind.TIMEOUT, e.getKind());
        // worker/engine still healthy after abort: a normal rule on a clean context works
        runtime.publish("r3ok", 1, "function decide(){ return {allowed:true}; }", quota);
        EvaluationResult ok = runtime.evaluate(new EvaluationRequest("r3ok",
                Map.of(), Map.of(), 1L));
        assertTrue(ok.getDecision().isAllowed());
        // and the same looping rule can still be attempted (fails again, doesn't hang host)
        ScriptExecutionException again = assertThrows(ScriptExecutionException.class,
                () -> runtime.evaluate(req("r3", 2L)));
        assertEquals(ExecutionErrorKind.TIMEOUT, again.getKind());
    }

    @Test
    void recursionLimit() {
        runtime.publish("r4", 1,
                "function decide(){ function f(n){ return n<=0?0:1+f(n-1);} return {allowed:f(100000)>=0}; }",
                quota);
        ScriptExecutionException e = assertThrows(ScriptExecutionException.class,
                () -> runtime.evaluate(req("r4", 1L)));
        assertEquals(ExecutionErrorKind.RECURSION_LIMIT, e.getKind());
    }

    @Test
    void hotSwapAndRollback() {
        runtime.publish("r5", 1, "function decide(){ return {allowed:true, reason:'v1'}; }", quota);
        assertEquals("v1", runtime.evaluate(req("r5", 1L)).getDecision().getReason());
        runtime.publish("r5", 2, "function decide(){ return {allowed:true, reason:'v2'}; }", quota);
        assertEquals("v2", runtime.evaluate(req("r5", 1L)).getDecision().getReason());
        runtime.rollback("r5", 1);
        int cur = runtime.currentVersion("r5");
        assertEquals(3, cur); // rollback appends a new monotonic version with restored content
        assertEquals("v1", runtime.evaluate(req("r5", 1L)).getDecision().getReason());
    }

    @Test
    void determinismSameSeed() {
        runtime.publish("r6", 1,
                "function decide(){ var x=0; for(var i=0;i<1000;i++){x+=Math.random();} return {allowed:true, reason:String(x)}; }",
                quota);
        String a = runtime.evaluate(req("r6", 42L)).getDecision().getReason();
        String b = runtime.evaluate(req("r6", 42L)).getDecision().getReason();
        String c = runtime.evaluate(req("r6", 43L)).getDecision().getReason();
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
