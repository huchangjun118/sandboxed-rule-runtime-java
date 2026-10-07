package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import com.github.huchangjun118.sandboxedruleruntimejava.exception.SandboxDeniedException;
import com.github.huchangjun118.sandboxedruleruntimejava.model.DenyReason;

import java.util.function.Predicate;

/**
 * The single source of truth for what user scripts may touch.
 *
 * <p>Two layers enforce it:</p>
 * <ol>
 *   <li><b>Pre-execution:</b> the Graal context is created with every sensitive
 *       permission switched off (IO, process, native, threads, environment, polyglot,
 *       host access), so access is rejected before any script byte runs.</li>
 *   <li><b>Boundary:</b> host-class lookups are routed through {@link #hostClassPredicate()}
 *       which classifies the requested type and throws a {@link SandboxDeniedException}
 *       carrying a distinct {@link DenyReason}.</li>
 * </ol>
 */
public final class SandboxPolicy {

    /** File-system APIs. */
    private static final String[] FILE_PACKAGES = {
            "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream",
            "java.io.RandomAccessFile", "java.io.FileReader", "java.io.FileWriter",
            "java.nio.file", "java.nio.channels.FileChannel", "java.io.WinNTFileSystem",
            "java.io.UnixFileSystem"
    };

    /** Process / command execution APIs. */
    private static final String[] PROCESS_CLASSES = {
            "java.lang.ProcessBuilder", "java.lang.Process", "java.lang.ProcessImpl",
            "java.lang.Runtime"
    };

    /** Network / socket APIs. */
    private static final String[] NETWORK_PREFIXES = {
            "java.net.", "javax.net.", "java.nio.channels.Socket",
            "java.nio.channels.ServerSocket", "java.nio.channels.Datagram"
    };

    /** Reflection and class-loading APIs. */
    private static final String[] REFLECTION_PREFIXES = {
            "java.lang.reflect.", "java.lang.Class", "java.lang.ClassLoader",
            "java.lang.invoke.", "jdk.internal.reflect."
    };

    /** Threading APIs. */
    private static final String[] THREAD_CLASSES = {
            "java.lang.Thread", "java.lang.ThreadGroup", "java.util.concurrent."
    };

    /** Native / FFI / unsafe APIs. */
    private static final String[] NATIVE_PREFIXES = {
            "jdk.internal.misc.Unsafe", "sun.misc.Unsafe", "java.lang.foreign.",
            "com.sun.jna.", "org.graalvm.nativeimage."
    };

    /** Environment access. */
    private static final String PROCESS_ENVIRONMENT = "java.lang.ProcessEnvironment";

    public boolean allowFileSystem() { return false; }
    public boolean allowProcess() { return false; }
    public boolean allowNetwork() { return false; }
    public boolean allowReflection() { return false; }
    public boolean allowThreads() { return false; }
    public boolean allowNative() { return false; }
    public boolean allowEnvironment() { return false; }

    /**
     * Classify a requested host class name into the capability category it would open.
     * Package-prefix and exact-name matching is used deliberately: a script only reaches
     * this method when it already attempts {@code Java.type(...)}, which is itself denied
     * wholesale; the finer categorisation exists to report precise reasons.
     */
    public static DenyReason reasonFor(String name) {
        if (name == null) {
            return DenyReason.HOST_ACCESS;
        }
        String n = name.replace('/', '.');
        for (String p : FILE_PACKAGES) {
            if (n.startsWith(p)) {
                return DenyReason.FILE_SYSTEM;
            }
        }
        for (String c : PROCESS_CLASSES) {
            if (n.startsWith(c)) {
                return DenyReason.PROCESS;
            }
        }
        for (String p : NETWORK_PREFIXES) {
            if (n.startsWith(p)) {
                return DenyReason.NETWORK;
            }
        }
        for (String p : REFLECTION_PREFIXES) {
            if (n.startsWith(p)) {
                return DenyReason.REFLECTION;
            }
        }
        for (String c : THREAD_CLASSES) {
            if (n.startsWith(c)) {
                return DenyReason.THREAD;
            }
        }
        for (String p : NATIVE_PREFIXES) {
            if (n.startsWith(p)) {
                return DenyReason.NATIVE;
            }
        }
        if (n.startsWith(PROCESS_ENVIRONMENT)) {
            return DenyReason.ENVIRONMENT;
        }
        return DenyReason.HOST_ACCESS;
    }

    /**
     * Predicate plugged into {@code Context.Builder.allowHostClassLookup}. Every lookup is
     * denied (returns false). Because GraalJS replaces an exception thrown from this
     * predicate with its own generic TypeError, the requested class name is recorded in a
     * thread-local first; the engine reads it when mapping the resulting guest error,
     * producing a {@link SandboxDeniedException} with the precise {@link DenyReason}.
     */
    public Predicate<String> hostClassPredicate() {
        return className -> {
            lastDeniedClass.set(className);
            return false;
        };
    }

    /** Most recently denied host class on the current worker thread. */
    private final ThreadLocal<String> lastDeniedClass = new ThreadLocal<>();

    public String lastDeniedClass() {
        return lastDeniedClass.get();
    }

    public void clearDenial() {
        lastDeniedClass.remove();
    }

    /** Build the denial exception for the most recently requested class on this thread. */
    public SandboxDeniedException denialForLast(String defaultClassName) {
        String className = lastDeniedClass.get();
        if (className == null) {
            className = defaultClassName;
        }
        DenyReason reason = reasonFor(className);
        return new SandboxDeniedException(reason,
                "access to host class " + className + " (" + reason
                        + ") is forbidden in the sandbox");
    }
}
