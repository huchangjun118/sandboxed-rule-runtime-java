package com.github.huchangjun118.sandboxedruleruntimejava.runtime;

import com.github.huchangjun118.sandboxedruleruntimejava.config.RuntimeProperties;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.ExecutionErrorKind;
import com.github.huchangjun118.sandboxedruleruntimejava.exception.ScriptExecutionException;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationResult;
import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.AtomicInFlightTracker;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.AtomicVersionRegistry;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.RuleVersion;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.VersionConflictException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.GraalSandboxEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Default {@link RuleRuntime} combining the atomic version registry, the isolated worker
 * pool and the Graal sandbox engine.
 *
 * <p>Concurrency guarantees:</p>
 * <ul>
 *   <li>The current version is read <em>once</em> and pinned for the whole evaluation, so
 *       a hot-swap mid-flight never causes a request to be judged twice or against a
 *       mixed version; arrivals during a swap see either the old or the new version.</li>
 *   <li>Every execution is dispatched to the bounded pool with a wall-clock watchdog; on
 *       timeout the worker's polyglot context is force-closed ({@code close(true)}), which
 *       aborts even tight interpreter loops. The discarded execution is then thrown away.
 *       Pooled worker threads are not reused until their task fully returns, so an aborted
 *       run can never contaminate a later one.</li>
 *   <li>Memory is enforced inside the engine by a per-thread allocation watcher that
 *       force-closes the context on breach and discards the execution.</li>
 * </ul>
 */
public final class DefaultRuleRuntime implements RuleRuntime {

    private final AtomicVersionRegistry registry;
    private final AtomicInFlightTracker inFlightTracker = new AtomicInFlightTracker();
    private final BoundedSandboxExecutor executor;
    private final GraalSandboxEngine sandboxEngine;
    private final ScheduledExecutorService watchdog;
    private final ResourceQuota defaultQuota;
    /** Shared engine used purely to compile/validate sources before publishing. */
    private final Engine compileEngine;
    /** Live context of the evaluation currently running on each worker thread. */
    private final ConcurrentMap<Thread, Context> activeContexts = new ConcurrentHashMap<>();

    public DefaultRuleRuntime(RuntimeProperties props, GraalSandboxEngine sandboxEngine) {
        this.sandboxEngine = sandboxEngine;
        this.executor = new BoundedSandboxExecutor(
                props.getWorkerThreads(),
                Math.max(4, props.getWorkerThreads() * 2),
                512L * 1024L);
        this.defaultQuota = new ResourceQuota(
                props.getDefaultTimeoutMillis(),
                props.getDefaultMemoryBytes(),
                props.getDefaultOutputBytes(),
                props.getDefaultRecursionDepth(),
                props.getDefaultStatementLimit());
        this.compileEngine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        this.registry = new AtomicVersionRegistry(this::compile, props.getHistoryPerRule());
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rule-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public ResourceQuota defaultQuota() {
        return defaultQuota;
    }

    /** Parse and validate source before a version can be committed. Never mutates state. */
    private Object compile(String ruleId, int version, String source) {
        try (Context context = Context.newBuilder("js")
                .engine(compileEngine)
                .build()) {
            Source src = Source.newBuilder("js", source, ruleId + "@" + version + ".js").build();
            context.parse(src); // throws PolyglotException on syntax errors
            return src;
        } catch (PolyglotException e) {
            throw new ScriptExecutionException(ExecutionErrorKind.COMPILE_ERROR,
                    e.getMessage() == null ? "compile error" : e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void publish(String ruleId, int version, String source, ResourceQuota quota) {
        registry.publish(ruleId, version, source, quota == null ? defaultQuota : quota);
    }

    @Override
    public int currentVersion(String ruleId) {
        return registry.current(ruleId).map(RuleVersion::getVersion).orElse(-1);
    }

    @Override
    public void rollback(String ruleId, int targetVersion) {
        registry.rollback(ruleId, targetVersion);
    }

    public List<RuleVersion> history(String ruleId) {
        return registry.history(ruleId);
    }

    public int inFlight(String ruleId, int version) {
        return inFlightTracker.inFlight(ruleId, version);
    }

    @Override
    public EvaluationResult evaluate(EvaluationRequest request) {
        RuleVersion version = registry.current(request.getRuleId())
                .orElseThrow(() -> new VersionConflictException(
                        "unknown rule " + request.getRuleId()));

        inFlightTracker.acquired(version);
        try {
            return dispatch(version, request);
        } finally {
            inFlightTracker.released(version);
        }
    }

    private EvaluationResult dispatch(RuleVersion version, EvaluationRequest request) {
        long timeoutMillis = version.getQuota().getTimeoutMillis();
        final Thread[] workerHolder = new Thread[1];

        Future<EvaluationResult> future;
        try {
            future = executor.submit(() -> {
                Thread worker = Thread.currentThread();
                workerHolder[0] = worker;
                sandboxEngine.setRegistrar(new GraalSandboxEngine.ContextRegistrar() {
                    @Override
                    public void register(Context context) {
                        activeContexts.put(worker, context);
                    }

                    @Override
                    public void clear() {
                        activeContexts.remove(worker);
                    }
                });
                try {
                    return sandboxEngine.evaluate(version, request);
                } finally {
                    activeContexts.remove(worker);
                    sandboxEngine.setRegistrar(null);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new ScriptExecutionException(ExecutionErrorKind.REJECTED,
                    "sandbox overloaded, try again later", e);
        }

        // Poll for the live context then force-close it at the deadline. close(true)
        // unwinds even non-yielding interpreter loops on the worker thread.
        watchdog.schedule(() -> {
            Thread w = workerHolder[0];
            if (w != null) {
                Context ctx = activeContexts.get(w);
                if (ctx != null) {
                    try {
                        ctx.close(true);
                    } catch (Throwable ignored) {
                        // already closed/closing
                    }
                }
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        try {
            return future.get(timeoutMillis + 250L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ScriptExecutionException(ExecutionErrorKind.TIMEOUT,
                    "execution exceeded " + timeoutMillis + "ms", e);
        } catch (CancellationException e) {
            throw new ScriptExecutionException(ExecutionErrorKind.TIMEOUT,
                    "execution cancelled after timeout", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ScriptExecutionException(ExecutionErrorKind.INTERNAL, "interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ScriptExecutionException see) {
                throw see;
            }
            // The watchdog's force-close can surface on the worker as an interrupted
            // guest exception rather than a mapped TIMEOUT; normalise that here.
            if (cause instanceof PolyglotException polyglot
                    && (polyglot.isCancelled() || isInterruption(polyglot))) {
                throw new ScriptExecutionException(ExecutionErrorKind.TIMEOUT,
                        "execution cancelled by watchdog after "
                                + timeoutMillis + "ms", polyglot);
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new ScriptExecutionException(ExecutionErrorKind.INTERNAL,
                    cause == null ? "internal error" : cause.toString(), cause);
        }
    }

    private static boolean isInterruption(PolyglotException e) {
        String message = e.getMessage();
        return message != null && message.contains("Thread was interrupted");
    }

    @Override
    public void shutdown() {
        watchdog.shutdownNow();
        executor.shutdown();
        compileEngine.close(true);
    }
}
