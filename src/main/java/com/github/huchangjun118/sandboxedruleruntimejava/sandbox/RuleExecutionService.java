package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script.AstNode;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script.Parser;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.validator.SandboxValidator;

import java.util.List;
import java.util.Optional;

/**
 * Application-facing facade over the version registry and the isolated
 * executor.
 *
 * <p>Version semantics enforced here:</p>
 * <ul>
 *   <li>{@link #publish} fully tokenizes, parses and sandbox-scans a script
 *       and only then commits a new immutable release; on any failure the old
 *       committed version stays fully visible (no partial state, no mixed
 *       fields - preparation happens entirely before the atomic pointer
 *       swap);</li>
 *   <li>{@link #execute(String, Object, ResourceLimits, Long)} takes a
 *       snapshot of the currently committed version before dispatch, so a hot
 *       replacement arriving during the call neither loses the request nor
 *       makes it judged twice, and an in-flight old version always finishes on
 *       its own release;</li>
 *   <li>{@link #rollback} repoints the current reference at an existing
 *       immutable release.</li>
 * </ul>
 */
public final class RuleExecutionService implements AutoCloseable {

    private final RuleStore store;
    private final RuleExecutor executor;
    private final SandboxValidator validator = new SandboxValidator();

    public RuleExecutionService() {
        this(new RuleStore(), new RuleExecutor(16, ResourceLimits.DEFAULT));
    }

    public RuleExecutionService(int workerThreads, ResourceLimits defaultLimits) {
        this(new RuleStore(), new RuleExecutor(workerThreads, defaultLimits));
    }

    public RuleExecutionService(RuleStore store, RuleExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    /**
     * Validate + sandbox-scan + atomically publish the next monotonic version.
     */
    public ScriptVersion publish(String ruleId, String source) {
        return publish(ruleId, null, source);
    }

    /**
     * Validate + sandbox-scan + atomically publish.
     *
     * @param requestedVersion explicit number, must be strictly greater than
     *                         the current one; {@code null} assigns the next
     */
    public ScriptVersion publish(String ruleId, Long requestedVersion, String source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        // Full preparation happens before any store mutation.
        AstNode.Program program = Parser.parse(source);
        validator.validate(program);

        long now = System.currentTimeMillis();
        if (requestedVersion != null) {
            return store.commit(ruleId, requestedVersion, source, program, now);
        }
        return store.commitNext(ruleId, source, program, now);
    }

    public ScriptVersion rollback(String ruleId, long targetVersion) {
        return store.rollback(ruleId, targetVersion);
    }

    public ExecutionResult execute(String ruleId, Object input) {
        return execute(ruleId, input, null, null);
    }

    public ExecutionResult execute(String ruleId, Object input, Long seed) {
        return execute(ruleId, input, null, seed);
    }

    public ExecutionResult execute(String ruleId, Object input,
                                   ResourceLimits limits, Long seed) {
        // Snapshot the release before dispatch: replacement/rollback during
        // execution cannot change this reference.
        ScriptVersion version = store.current(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));
        return executor.execute(version, input, limits, seed);
    }

    public Optional<ScriptVersion> current(String ruleId) {
        return store.current(ruleId);
    }

    public List<Long> versions(String ruleId) {
        return store.versions(ruleId);
    }

    @Override
    public void close() {
        executor.close();
    }
}
