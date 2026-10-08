package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script.AstNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Versioned rule registry.
 *
 * <p>Version semantics:</p>
 * <ul>
 *   <li>a rule script is managed as an immutable, fully prepared release unit
 *       ({@link ScriptVersion});</li>
 *   <li>at any instant each rule id points at exactly zero or one committed
 *       version - readers observe either the previous release or the whole new
 *       release, never a half-parsed / field-mixed state (the new version is
 *       fully built, validated and sandbox-scanned before the single atomic
 *       pointer swap);</li>
 *   <li>committed version numbers strictly increase;</li>
 *   <li>rollback points the current reference at a previously committed
 *       immutable release; no version is rewritten or deleted;</li>
 *   <li>an in-flight evaluation captured its own {@link ScriptVersion} before
 *       dispatch, so hot replacement cannot change what it executes and an old
 *       version call always finishes on the old version.</li>
 * </ul>
 */
public final class RuleStore {

    private static final class Entry {
        private final AtomicReference<ScriptVersion> current = new AtomicReference<>();
        private final List<ScriptVersion> history = new ArrayList<>();
        private long lastVersion = 0L;
    }

    private final ConcurrentHashMap<String, Entry> rules = new ConcurrentHashMap<>();

    public RuleStore() {
    }

    /**
     * Atomically publish a new prepared release.
     *
     * @param requestedVersion caller supplied, strictly increasing number
     * @throws IllegalArgumentException on duplicate/non-increasing version
     */
    public ScriptVersion commit(String ruleId, long requestedVersion, String source,
                                AstNode.Program program, long nowMillis) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        if (requestedVersion <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        Entry entry = rules.computeIfAbsent(ruleId, k -> new Entry());
        synchronized (entry) {
            if (requestedVersion <= entry.lastVersion) {
                throw new IllegalArgumentException(
                        "version " + requestedVersion + " for rule '" + ruleId
                                + "' is not greater than the last committed version "
                                + entry.lastVersion);
            }
            ScriptVersion version = new ScriptVersion(
                    ruleId, requestedVersion, source, program, nowMillis);
            entry.history.add(version);
            entry.lastVersion = requestedVersion;
            entry.current.set(version);
            return version;
        }
    }

    /**
     * Atomically assign the next monotonic version number and publish a fully
     * prepared release. Number assignment and the pointer swap share one lock,
     * so concurrent publishes can never collide on a version.
     */
    public ScriptVersion commitNext(String ruleId, String source,
                                    AstNode.Program program, long nowMillis) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        Entry entry = rules.computeIfAbsent(ruleId, k -> new Entry());
        synchronized (entry) {
            long requestedVersion = entry.lastVersion + 1L;
            ScriptVersion version = new ScriptVersion(
                    ruleId, requestedVersion, source, program, nowMillis);
            entry.history.add(version);
            entry.lastVersion = requestedVersion;
            entry.current.set(version);
            return version;
        }
    }

    /** Point the committed reference back at an existing immutable release. */
    public ScriptVersion rollback(String ruleId, long targetVersion) {
        Entry entry = rules.get(ruleId);
        if (entry == null) {
            throw new RuleNotFoundException(ruleId);
        }
        synchronized (entry) {
            ScriptVersion target = null;
            for (ScriptVersion v : entry.history) {
                if (v.version() == targetVersion) {
                    target = v;
                    break;
                }
            }
            if (target == null) {
                throw new IllegalArgumentException("rule '" + ruleId
                        + "' has no committed version " + targetVersion);
            }
            entry.current.set(target);
            return target;
        }
    }

    public Optional<ScriptVersion> current(String ruleId) {
        Entry entry = rules.get(ruleId);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.current.get());
    }

    public List<Long> versions(String ruleId) {
        Entry entry = rules.get(ruleId);
        if (entry == null) {
            return List.of();
        }
        synchronized (entry) {
            return entry.history.stream()
                    .map(ScriptVersion::version)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }
}
