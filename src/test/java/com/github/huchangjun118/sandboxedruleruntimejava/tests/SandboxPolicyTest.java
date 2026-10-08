package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleExecutionService;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimits;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.SandboxViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement 2: capability requests (file system, process, network,
 * runtime/reflection and any other non-sandbox surface) must be rejected
 * before execution starts, with distinguishable reason codes.
 */
class SandboxPolicyTest {

    private RuleExecutionService service;

    @BeforeEach
    void setUp() {
        service = new RuleExecutionService(4, ResourceLimits.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private void assertRejected(String source, String reasonCode) {
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
                () -> service.publish("rule", source));
        assertEquals(reasonCode, ex.reasonCode(),
                "expected " + reasonCode + " for: " + source);
    }

    @Test
    void rejectsFileSystemAccess() {
        assertRejected("return readFile(\"/etc/passwd\");",
                SandboxViolationException.FILESYSTEM);
        assertRejected("return Files.read(\"a.txt\");",
                SandboxViolationException.FILESYSTEM);
        assertRejected("return fs.write(\"x\", \"y\");",
                SandboxViolationException.FILESYSTEM);
        assertRejected("return File.path;",
                SandboxViolationException.FILESYSTEM);
    }

    @Test
    void rejectsProcessAccess() {
        assertRejected("return exec(\"rm -rf /\");", SandboxViolationException.PROCESS);
        assertRejected("return system(\"id\");", SandboxViolationException.PROCESS);
        assertRejected("return Runtime.exec(\"sh\");", SandboxViolationException.PROCESS);
        assertRejected("return ProcessBuilder.start(1);", SandboxViolationException.PROCESS);
        assertRejected("return exit(1);", SandboxViolationException.PROCESS);
        assertRejected("return getenv(\"HOME\");", SandboxViolationException.PROCESS);
    }

    @Test
    void rejectsNetworkAccess() {
        assertRejected("return fetch(\"http://x\");", SandboxViolationException.NETWORK);
        assertRejected("return http.get(\"http://x\");", SandboxViolationException.NETWORK);
        assertRejected("return openSocket(\"x\", 80);", SandboxViolationException.NETWORK);
        assertRejected("return url.openConnection(1);", SandboxViolationException.NETWORK);
    }

    @Test
    void rejectsReflectionAndRuntimeInternals() {
        assertRejected("return eval(\"1+1\");", SandboxViolationException.REFLECTION);
        assertRejected("return Class.forName(\"java.lang.Runtime\");",
                SandboxViolationException.REFLECTION);
        assertRejected("return System.exit(0);", SandboxViolationException.REFLECTION);
        assertRejected("return Thread.currentThread();", SandboxViolationException.REFLECTION);
        assertRejected("return ClassLoader.getSystemClassLoader(1);",
                SandboxViolationException.REFLECTION);
        assertRejected("return input.getClass();", SandboxViolationException.REFLECTION);
    }

    @Test
    void rejectsUnknownCapabilitiesWithGenericCode() {
        assertRejected("return require(\"crypto\");", SandboxViolationException.DENIED);
        assertRejected("return java.version;", SandboxViolationException.DENIED);
        assertRejected("return globalThis.x;", SandboxViolationException.DENIED);
        assertRejected("return totallyUnknownThing(1);", SandboxViolationException.DENIED);
    }

    @Test
    void rejectionHappensBeforeExecutionAndKeepsOldVersion() {
        service.publish("rule", "return true;");
        // Dangerous publish fails and never becomes the committed version.
        assertThrows(SandboxViolationException.class,
                () -> service.publish("rule", "return exec(\"id\");"));
        Object result = service.execute("rule", null).result();
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void reasonCodesAreDistinguishable() {
        String fs = assertCode("return readFile(\"x\");");
        String pr = assertCode("return exec(\"x\");");
        String net = assertCode("return fetch(\"x\");");
        String rf = assertCode("return eval(\"x\");");
        String denied = assertCode("return require(\"x\");");

        long distinct = java.util.stream.Stream.of(fs, pr, net, rf, denied).distinct().count();
        assertEquals(5, distinct);
        assertTrue(fs.startsWith("SANDBOX_"));
    }

    private String assertCode(String source) {
        return assertThrows(SandboxViolationException.class,
                () -> service.publish("r" + System.nanoTime(), source)).reasonCode();
    }

    @Test
    void safeBuiltinsAreAccepted() {
        service.publish("ok", """
                var a = [1, 2, 3];
                var m = max(1, 2);
                var s = upper("abc");
                var r = random() >= 0;
                return len(a) == 3 && m == 2 && s == "ABC" && r;
                """);
        assertEquals(Boolean.TRUE, service.execute("ok", null).result());
    }

    @Test
    void deniedCallInsideFunctionBodyIsScanned() {
        assertRejected("""
                function helper() {
                    return fetch("http://x");
                }
                return helper();
                """, SandboxViolationException.NETWORK);
    }

    @Test
    void deniedCallInsideNestedBlockIsScanned() {
        assertRejected("""
                if (true) {
                    while (false) {
                        return exec("id");
                    }
                }
                return false;
                """, SandboxViolationException.PROCESS);
    }

    @Test
    void deniedCallInsideForLoopIsScanned() {
        assertRejected("""
                for (x in [1,2,3]) {
                    readFile("/etc/passwd");
                }
                return true;
                """, SandboxViolationException.FILESYSTEM);
    }
}
