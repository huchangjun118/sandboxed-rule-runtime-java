package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import com.github.huchangjun118.sandboxedruleruntimejava.exception.ExecutionErrorKind;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.SandboxDeniedException;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.ScriptExecutionException;
import com.github.huchangjun118.sandboxedruleruntimejava.model.Decision;
import com.github.huchangjun118.sandboxedruleruntimejava.model.DenyReason;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationResult;
import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.RuleVersion;
import com.github.huchangjun118.sandboxedruleruntimejava.runtime.SandboxEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraalJS backed {@link SandboxEngine}. Each evaluation gets:
 * <ul>
 *   <li>a brand-new {@link Context} (no globals survive between calls) sharing only a
 *       pooled {@link Engine} (code cache) — giving isolation without recompilation cost;</li>
 *   <li>every host permission disabled before execution (IO/process/native/thread/env/
 *       polyglot/host-access), plus the {@link SandboxPolicy} class-lookup predicate;</li>
 *   <li>statement, recursion-depth and output quotas enforced inside the engine;</li>
 *   <li>a deterministic prelude keyed by the requested seed;</li>
 *   <li>timeout and memory enforced by the surrounding runtime (watchdog) so an aborted
 *       execution is fully discarded (context closed) and can never affect later calls.</li>
 * </ul>
 */
public final class GraalSandboxEngine implements SandboxEngine {

    private static final String LANGUAGE = "js";

    /** Notified of the live context of the evaluation running on the current thread, so
     *  an external watchdog can force-close it on timeout. */
    public interface ContextRegistrar {
        void register(Context context);
        void clear();
    }

    private final SandboxPolicy policy;
    /**
     * Per-worker-thread registrar of the live context. The runtime installs one before
     * dispatching an evaluation and removes it afterwards; the watchdog reads the context
     * by worker thread. Using a thread-local keeps this engine a singleton while each
     * concurrent evaluation still reports its distinct context.
     */
    private final ThreadLocal<ContextRegistrar> registrar = new ThreadLocal<>();
    private record EngineHandle(Engine engine, RecursionDepthInstrument instrument) {
    }

    /** Shared engine keyed by recursion limit; engines are expensive to create. */
    private final Map<Integer, EngineHandle> engines = new HashMap<>();

    public GraalSandboxEngine(SandboxPolicy policy) {
        this.policy = policy;
    }

    /** Install the registrar for the calling worker thread for the duration of the action. */
    public void setRegistrar(ContextRegistrar contextRegistrar) {
        if (contextRegistrar == null) {
            registrar.remove();
        } else {
            registrar.set(contextRegistrar);
        }
    }

    public SandboxPolicy getPolicy() {
        return policy;
    }

    private synchronized EngineHandle engineFor(int recursionLimit) {
        return engines.computeIfAbsent(recursionLimit, limit -> {
            Engine engine = Engine.newBuilder(LANGUAGE)
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();
            RecursionDepthInstrument instrument = engine.getInstruments()
                    .get(RecursionDepthInstrument.ID)
                    .lookup(RecursionDepthInstrument.class);
            if (instrument == null) {
                engine.close(true);
                throw new IllegalStateException("recursion depth instrument unavailable");
            }
            instrument.setLimit(limit);
            return new EngineHandle(engine, instrument);
        });
    }

    @Override
    public EvaluationResult evaluate(RuleVersion version, EvaluationRequest request) {
        resetDenialTracking();
        ResourceQuota quota = version.getQuota();
        CappedOutputBuffer output = new CappedOutputBuffer(quota.getOutputBytes());
        long start = System.nanoTime();

        EngineHandle handle = engineFor(quota.getMaxRecursionDepth());
        // Per-thread recursion counter must start at zero for every evaluation, since
        // worker threads (and the instrument) are reused across calls.
        handle.instrument().reset();
        Context.Builder builder = Context.newBuilder(LANGUAGE)
                .engine(handle.engine())
                // --- sandbox lockdown: denied before any script statement runs ---
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(policy.hostClassPredicate())
                .allowHostClassLoading(false)
                .allowIO(false)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowAllAccess(false)
                // --- quotas enforced inside the guest ---
                .resourceLimits(ResourceLimits.newBuilder()
                        .statementLimit(quota.getStatementLimit(), null)
                        .build())
                .out(output)
                .err(output);

        long baseline = MemoryWatcher.allocatedByCurrentThread();

        ContextRegistrar currentRegistrar = registrar.get();
        Context context = builder.build();
        if (currentRegistrar != null) {
            currentRegistrar.register(context);
        }
        MemoryWatcher memoryWatcher = new MemoryWatcher(
                Thread.currentThread(), baseline, quota.getMemoryBytes(), 2L,
                () -> { try { context.close(true); } catch (Throwable ignored) { } });
        memoryWatcher.start();
        try {
            context.initialize(LANGUAGE);

            // Deterministic globals (Math.random/Date/performance.now keyed by seed).
            long fixedEpoch = 1_700_000_000_000L + (request.getSeed() % 86_400_000L);
            context.eval(LANGUAGE,
                    DeterminismPrelude.build(request.getSeed(), fixedEpoch));

            Value bindings = context.getBindings(LANGUAGE);
            bindings.putMember("input", ProxyObject.fromMap(new HashMap<>(request.getInput())));
            bindings.putMember("ctx", ProxyObject.fromMap(new HashMap<>(request.getContext())));
            bindings.putMember("seed", request.getSeed());

            // Compile/run the rule source, then invoke its decide(input, ctx) entry point.
            Value scriptValue;
            try {
                scriptValue = context.eval(LANGUAGE, version.getSource());
            } catch (PolyglotException e) {
                throw mapFailure(e, output, memoryWatcher, quota);
            }

            Value decisionValue;
            try {
                if (scriptValue != null && scriptValue.canExecute()) {
                    // Whole script is a function: call it directly.
                    decisionValue = scriptValue.execute(
                            bindings.getMember("input"), bindings.getMember("ctx"));
                } else {
                    Value decide = bindings.getMember("decide");
                    if (decide == null || !decide.canExecute()) {
                        throw new ScriptExecutionException(ExecutionErrorKind.SCRIPT_ERROR,
                                "rule script must define decide(input, ctx) or be a function");
                    }
                    decisionValue = decide.execute(
                            bindings.getMember("input"), bindings.getMember("ctx"));
                }
            } catch (PolyglotException e) {
                throw mapFailure(e, output, memoryWatcher, quota);
            }

            if (memoryWatcher.isExceeded()) {
                throw new ScriptExecutionException(ExecutionErrorKind.MEMORY_LIMIT,
                        "allocation exceeded " + quota.getMemoryBytes() + " bytes");
            }
            Decision decision = toDecision(decisionValue);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            return new EvaluationResult(version.getRuleId(), version.getVersion(),
                    decision, output.asString(), output.isOverflowed(), elapsedMs);
        } finally {
            memoryWatcher.stop();
            try {
                context.close(true);
            } finally {
                if (currentRegistrar != null) {
                    currentRegistrar.clear();
                }
            }
        }
    }

    /** Convert a guest result into the stable {@link Decision} shape. */
    private Decision toDecision(Value value) {
        if (value == null || value.isNull()) {
            return new Decision(false, "", Map.of());
        }
        if (value.isBoolean()) {
            return new Decision(value.asBoolean(), "", Map.of());
        }
        if (value.hasMembers()) {
            boolean allowed = readBoolean(value, "allowed", readBoolean(value, "allow", false));
            String reason = readString(value, "reason", "");
            Map<String, Object> attributes = readMap(value.getMember("attributes"));
            return new Decision(allowed, reason, attributes);
        }
        return new Decision(false, String.valueOf(value), Map.of());
    }

    private boolean readBoolean(Value host, String key, boolean dflt) {
        Value v = host.getMember(key);
        return v != null && v.isBoolean() ? v.asBoolean() : dflt;
    }

    private String readString(Value host, String key, String dflt) {
        Value v = host.getMember(key);
        return v != null && v.isString() ? v.asString() : dflt;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Value value) {
        if (value == null || value.isNull() || !value.hasMembers()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : value.getMemberKeys()) {
            result.put(key, toJava(value.getMember(key)));
        }
        return result;
    }

    private Object toJava(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            return v.as(Object.class);
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.hasMembers()) {
            return readMap(v);
        }
        return String.valueOf(v);
    }

    /** Prefer quota breach signals (memory/output) over the generic cancellation reason. */
    private ScriptExecutionException mapFailure(PolyglotException e, CappedOutputBuffer output,
                                                MemoryWatcher memoryWatcher, ResourceQuota quota) {
        if (memoryWatcher.isExceeded()) {
            return new ScriptExecutionException(ExecutionErrorKind.MEMORY_LIMIT,
                    "allocation exceeded " + quota.getMemoryBytes() + " bytes", e);
        }
        Throwable host = e.isHostException() ? e.asHostException() : null;
        if (host instanceof CappedOutputBuffer.OutputLimitExceededException
                || output.isOverflowed()) {
            return new ScriptExecutionException(ExecutionErrorKind.OUTPUT_LIMIT,
                    "script output exceeded " + quota.getOutputBytes() + " bytes", e);
        }
        // A host-class predicate denial may surface directly or (commonly) be replaced by
        // GraalJS with a generic "Access to host class ... is not allowed" TypeError.
        // Detect that wording and recover the precise reason recorded by the predicate.
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("Access to host class") && msg.contains("not allowed")) {
            SandboxDeniedException denied = policy.denialForLast(extractClassName(msg));
            return new ScriptExecutionException(ExecutionErrorKind.DENIED,
                    denied.getReason(), denied.getMessage(), e);
        }
        // A host-class predicate denial may surface directly or be wrapped by GraalJS
        // inside a guest TypeError; inspect the cause chain either way.
        SandboxDeniedException denial = findDenial(e);
        if (denial != null) {
            return new ScriptExecutionException(ExecutionErrorKind.DENIED,
                    denial.getReason(), denial.getMessage(), e);
        }
        return mapPolyglot(e, output);
    }

    /** Pull the host class name out of GraalJS's generic denial message. */
    private String extractClassName(String message) {
        String marker = "Access to host class ";
        int start = message.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = message.indexOf(' ', start);
        return end < 0 ? message.substring(start) : message.substring(start, end);
    }

    /** Clear per-thread denial bookkeeping before an evaluation starts. */
    private void resetDenialTracking() {
        policy.clearDenial();
    }

    /** Walk the cause chain looking for a sandbox capability denial. */
    private SandboxDeniedException findDenial(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SandboxDeniedException denied) {
                return denied;
            }
        }
        return null;
    }

    /** Map every failure mode to a distinct, stable {@link ExecutionErrorKind}. */
    private ScriptExecutionException mapPolyglot(PolyglotException e, CappedOutputBuffer output) {
        // Force-cancellation by the wall-clock watchdog.
        if (e.isCancelled()) {
            return new ScriptExecutionException(ExecutionErrorKind.TIMEOUT,
                    "execution cancelled by watchdog", e);
        }
        // Host-side sandbox denial raised by the class-lookup predicate.
        if (e.isHostException()) {
            Throwable hostException = e.asHostException();
            if (hostException instanceof SandboxDeniedException denied) {
                return new ScriptExecutionException(ExecutionErrorKind.DENIED,
                        denied.getReason(), denied.getMessage(), e);
            }
            return new ScriptExecutionException(ExecutionErrorKind.INTERNAL,
                    hostException.toString(), e);
        }
        if (e.isResourceExhausted()) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Statement count")) {
                return new ScriptExecutionException(ExecutionErrorKind.STATEMENT_LIMIT, msg, e);
            }
            return new ScriptExecutionException(ExecutionErrorKind.RECURSION_LIMIT, msg, e);
        }
        if (e.isGuestException()) {
            String msg = e.getMessage() == null ? "script error" : e.getMessage();
            if (msg.contains("Thread was interrupted")) {
                return new ScriptExecutionException(ExecutionErrorKind.TIMEOUT,
                        "execution cancelled by watchdog", e);
            }
            if (msg.contains("recursion depth limit")) {
                return new ScriptExecutionException(ExecutionErrorKind.RECURSION_LIMIT, msg, e);
            }
            if (e.isSyntaxError()) {
                return new ScriptExecutionException(ExecutionErrorKind.COMPILE_ERROR, msg, e);
            }
            return new ScriptExecutionException(ExecutionErrorKind.SCRIPT_ERROR, msg, e);
        }
        return new ScriptExecutionException(ExecutionErrorKind.INTERNAL,
                e.getMessage() == null ? "internal error" : e.getMessage(), e);
    }
}
