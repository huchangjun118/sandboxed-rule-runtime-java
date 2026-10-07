package com.github.huchangjun118.sandboxedruleruntimejava.registry;

import java.util.List;
import java.util.Optional;

/**
 * Stores published rule versions with atomic publish semantics:
 * at any instant one rule id maps to exactly one committed version;
 * concurrent readers never observe a half-published version or torn fields.
 */
public interface VersionRegistry {

    /**
     * Atomically publish a new version. The version number must be greater than the
     * current committed version for that rule id. Implementations compile/prepare the
     * script <em>before</em> the atomic swap, so a failing publish never replaces the
     * current good version.
     *
     * @return the committed version snapshot
     */
    RuleVersion publish(String ruleId, int version, String source,
                        com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota quota);

    /** Current committed version, or empty if the rule id is unknown. */
    Optional<RuleVersion> current(String ruleId);

    /**
     * Atomically re-point the rule id at a previously published version.
     * Requests arriving during the rollback see either the old or the restored version,
     * never both and never none.
     */
    RuleVersion rollback(String ruleId, int targetVersion);

    /** Immutable ordered history (oldest first) of versions ever committed for a rule. */
    List<RuleVersion> history(String ruleId);
}
