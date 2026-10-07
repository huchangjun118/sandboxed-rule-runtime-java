package com.github.huchangjun118.sandboxedruleruntimejava.registry;

import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory {@link VersionRegistry} providing atomic publish/rollback semantics.
 *
 * <p>Per rule id it holds an immutable {@link Record}: a reference to the single
 * committed current version plus an append-only history. Publishing compiles the source
 * first (which may fail without touching state), then performs one CAS-like swap of the
 * whole record. Readers therefore always observe either the previous complete record or
 * the new complete record, never a half-published version or torn fields.</p>
 *
 * <p>Old versions are retained in bounded history so in-flight evaluations that pinned a
 * version can finish, and so rollback can re-point at an earlier version.</p>
 */
public final class AtomicVersionRegistry implements VersionRegistry {

    /** Immutable per-rule state; replaced wholesale on every publish/rollback. */
    private record State(List<RuleVersion> history, RuleVersion current) {
        State {
            history = List.copyOf(history);
        }
    }

    private final Map<String, AtomicReference<State>> rules = new ConcurrentHashMap<>();
    private final SourceCompiler compiler;
    private final int historyLimit;

    public AtomicVersionRegistry(SourceCompiler compiler, int historyLimit) {
        this.compiler = compiler;
        this.historyLimit = Math.max(1, historyLimit);
    }

    @Override
    public RuleVersion publish(String ruleId, int version, String source, ResourceQuota quota) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new VersionConflictException("ruleId must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new VersionConflictException("source must not be blank");
        }
        // Prepare entirely before touching shared state. A failure here leaves the
        // current committed version completely intact.
        Object compiled = compiler.compile(ruleId, version, source);
        RuleVersion candidate = new RuleVersion(ruleId, version, source, quota, compiled);

        AtomicReference<State> ref = rules.computeIfAbsent(ruleId,
                k -> new AtomicReference<>(new State(List.of(), null)));

        while (true) {
            State snapshot = ref.get();
            if (snapshot.current() != null && version <= snapshot.current().getVersion()) {
                throw new VersionConflictException(
                        "version " + version + " is not greater than current version "
                                + snapshot.current().getVersion() + " for rule " + ruleId);
            }
            List<RuleVersion> nextHistory = appendBounded(snapshot.history(), candidate);
            State next = new State(nextHistory, candidate);
            if (ref.compareAndSet(snapshot, next)) {
                return candidate;
            }
        }
    }

    @Override
    public Optional<RuleVersion> current(String ruleId) {
        AtomicReference<State> ref = rules.get(ruleId);
        if (ref == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ref.get().current());
    }

    @Override
    public RuleVersion rollback(String ruleId, int targetVersion) {
        AtomicReference<State> ref = rules.get(ruleId);
        if (ref == null) {
            throw new VersionConflictException("unknown rule " + ruleId);
        }
        while (true) {
            State snapshot = ref.get();
            RuleVersion target = snapshot.history().stream()
                    .filter(v -> v.getVersion() == targetVersion)
                    .findFirst()
                    .orElseThrow(() -> new VersionConflictException(
                            "version " + targetVersion + " not found in history of rule " + ruleId));
            if (snapshot.current() != null && snapshot.current().getVersion() == targetVersion) {
                return snapshot.current();
            }
            // Re-publish as a new entry so history keeps a linear, monotonic audit trail:
            // rollback creates a new current snapshot pointing at the old content but with
            // a fresh version number greater than the current one.
            int newVersion = snapshot.current() == null ? targetVersion
                    : snapshot.current().getVersion() + 1;
            RuleVersion restored = new RuleVersion(ruleId, newVersion,
                    target.getSource(), target.getQuota(), target.getCompiledSource());
            State next = new State(appendBounded(snapshot.history(), restored), restored);
            if (ref.compareAndSet(snapshot, next)) {
                return restored;
            }
        }
    }

    @Override
    public List<RuleVersion> history(String ruleId) {
        AtomicReference<State> ref = rules.get(ruleId);
        return ref == null ? List.of() : ref.get().history();
    }

    private List<RuleVersion> appendBounded(List<RuleVersion> history, RuleVersion added) {
        List<RuleVersion> merged = new ArrayList<>(history.size() + 1);
        merged.addAll(history);
        merged.add(added);
        if (merged.size() > historyLimit) {
            merged = new ArrayList<>(merged.subList(merged.size() - historyLimit, merged.size()));
        }
        return merged;
    }
}
