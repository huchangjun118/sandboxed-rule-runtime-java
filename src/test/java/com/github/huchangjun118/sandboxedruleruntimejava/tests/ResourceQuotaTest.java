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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each per-execution quota (time is covered elsewhere) must be enforced and reported with
 * its own error kind, and after every abort the runtime must stay clean and usable.
 */
class ResourceQuotaTest {

    private DefaultRuleRuntime runtime;
    private ResourceQuota q;

    @BeforeEach
    void setUp() {
        RuntimeProperties props = new RuntimeProperties();
        props.setWorkerThreads(4);
        props.setDefaultTimeoutMillis(2000);
        runtime = new DefaultRuleRuntime(props, new GraalSandboxEngine(new SandboxPolicy()));
        q = runtime.defaultQuota();
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
    }

    private EvaluationRequest request(String id) {
        return new EvaluationRequest(id, Map.of(), Map.of(), 1L);
    }

    private ResourceQuota with(ResourceQuota base, long timeout, long memory, long output,
                               int depth, long statements) {
        return new ResourceQuota(timeout, memory, output, depth, statements);
    }

    @Test
    void outputLimitEnforced() {
        ResourceQuota small = with(q, q.getTimeoutMillis(), q.getMemoryBytes(),
                256, q.getMaxRecursionDepth(), q.getStatementLimit());
        runtime.publish("out", 1,
                "function decide(){ var s='x'.repeat(100000); print(s); return {allowed:true}; }",
                small);
        ScriptExecutionException e = assertThrows(ScriptExecutionException.class,
                () -> runtime.evaluate(request("out")));
        assertEquals(ExecutionErrorKind.OUTPUT_LIMIT, e.getKind());
        assertRuntimeClean();
    }

    @Test
    void statementLimitStopsBusyLoopWithoutWaitingForTimeout() {
        // Tight loop that does little allocation: statement budget trips well before timeout.
        ResourceQuota tight = with(q, 5000, q.getMemoryBytes(), q.getOutputBytes(),
                q.getMaxRecursionDepth(), 50_000);
        runtime.publish("stmt", 1,
                "function decide(){ var x=0; for(var i=0;i<1e12;i++){ x=(x*7+3)%997; } return {allowed:true}; }",
                tight);
        long start = System.currentTimeMillis();
        ScriptExecutionException e = assertThrows(ScriptExecutionException.class,
                () -> runtime.evaluate(request("stmt")));
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(ExecutionErrorKind.STATEMENT_LIMIT, e.getKind());
        assertTrue(elapsed < 3000, "statement limit should trip quickly, took " + elapsed + "ms");
        assertRuntimeClean();
    }

    @Test
    void recursionDepthEnforced() {
        ResourceQuota shallow = with(q, q.getTimeoutMillis(), q.getMemoryBytes(),
                q.getOutputBytes(), 50, q.getStatementLimit());
        runtime.publish("rec", 1,
                "function decide(){ function f(n){ return n<=0?0:1+f(n-1);} f(100000); return {allowed:true}; }",
                shallow);
        ScriptExecutionException e = assertThrows(ScriptExecutionException.class,
                () -> runtime.evaluate(request("rec")));
        assertEquals(ExecutionErrorKind.RECURSION_LIMIT, e.getKind());
        // After the abort the runtime is clean: publish a shallow version of the same rule
        // and confirm it evaluates normally on a fresh context.
        runtime.publish("rec", 2,
                "function decide(){ function f(n){ return n<=0?0:1+f(n-1);} return {allowed:f(10)===10}; }",
                shallow);
        EvaluationResult ok = runtime.evaluate(request("rec"));
        assertTrue(ok.getDecision().isAllowed());
    }

    @Test
    void memoryLimitEnforced() {
        ResourceQuota tightMem = with(q, 3000, 2L * 1024 * 1024, q.getOutputBytes(),
                q.getMaxRecursionDepth(), Long.MAX_VALUE);
        runtime.publish("mem", 1,
                "function decide(){ var a=[]; for(var i=0;i<50;i++){ a.push(new Array(1000000).fill(i)); } return {allowed:true}; }",
                tightMem);
        ScriptExecutionException e = assertThrows(ScriptExecutionException.class,
                () -> runtime.evaluate(request("mem")));
        assertEquals(ExecutionErrorKind.MEMORY_LIMIT, e.getKind());
        assertRuntimeClean();
    }

    private void assertRuntimeClean() {
        runtime.publish("clean", 1, "function decide(){ return {allowed:true}; }", q);
        EvaluationResult ok = runtime.evaluate(request("clean"));
        assertTrue(ok.getDecision().isAllowed());
    }
}
