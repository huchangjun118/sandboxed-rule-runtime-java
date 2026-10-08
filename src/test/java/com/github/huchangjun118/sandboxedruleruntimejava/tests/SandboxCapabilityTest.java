package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.config.RuntimeProperties;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.ExecutionErrorKind;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.ScriptExecutionException;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;
import com.github.huchangjun118.sandboxedruleruntimejava.runtime.DefaultRuleRuntime;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.GraalSandboxEngine;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.SandboxPolicy;
import com.github.huchangjun118.sandboxedruleruntimejava.model.DenyReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every forbidden capability must be rejected <em>and</em> reported with a reason that
 * lets callers distinguish file system, process, network, reflection and other probes.
 */
class SandboxCapabilityTest {

    private DefaultRuleRuntime runtime;
    private ResourceQuota quota;
    private int counter;

    @BeforeEach
    void setUp() {
        RuntimeProperties props = new RuntimeProperties();
        props.setWorkerThreads(4);
        runtime = new DefaultRuleRuntime(props, new GraalSandboxEngine(new SandboxPolicy()));
        quota = runtime.defaultQuota();
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
    }

    @ParameterizedTest
    @CsvSource({
            "java.io.File,                                   FILE_SYSTEM",
            "java.io.FileInputStream,                        FILE_SYSTEM",
            "java.nio.file.Paths,                            FILE_SYSTEM",
            "java.lang.ProcessBuilder,                       PROCESS",
            "java.lang.Runtime,                              PROCESS",
            "java.net.Socket,                                NETWORK",
            "java.net.URL,                                   NETWORK",
            "javax.net.ssl.SSLContext,                       NETWORK",
            "java.lang.reflect.Method,                       REFLECTION",
            "java.lang.ClassLoader,                          REFLECTION",
            "java.lang.invoke.MethodHandles,                 REFLECTION",
            "java.lang.Thread,                               THREAD",
            "java.util.concurrent.Executors,                 THREAD",
            "sun.misc.Unsafe,                                NATIVE"
    })
    void hostClassAccessIsDeniedWithDistinctReason(String className, String expected) throws Exception {
        // Direct policy classification (no Graal needed).
        assertEquals(DenyReason.valueOf(expected), SandboxPolicy.reasonFor(className.replaceAll("\\s+", "")));

        // End-to-end: a script attempting Java.type(...) is aborted with DENIED + reason.
        String id = "deny-" + (counter++);
        String src = "function decide(){ var C=Java.type('" + className.replaceAll("\\s+", "")
                + "'); return {allowed:false}; }";
        runtime.publish(id, 1, src, quota);
        ScriptExecutionException ex = (ScriptExecutionException) catchFailure(id);
        assertEquals(ExecutionErrorKind.DENIED, ex.getKind());
        assertEquals(DenyReason.valueOf(expected), ex.getDenyReason(),
                "denial for " + className + " must be distinguishable");
    }

    @Test
    void loadClassTrickAlsoDeniedAsReflection() {
        String src = "function decide(){ var C=Java.type('java.lang.Class'); return {allowed:false}; }";
        runtime.publish("deny-cls", 1, src, quota);
        ScriptExecutionException ex = (ScriptExecutionException) catchFailure("deny-cls");
        assertEquals(ExecutionErrorKind.DENIED, ex.getKind());
        assertEquals(DenyReason.REFLECTION, ex.getDenyReason());
    }

    private Throwable catchFailure(String id) {
        try {
            runtime.evaluate(new EvaluationRequest(id, Map.of(), Map.of(), 1L));
            throw new AssertionError("expected denial");
        } catch (ScriptExecutionException e) {
            return e;
        }
    }
}
