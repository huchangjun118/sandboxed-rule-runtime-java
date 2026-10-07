package com.github.huchangjun118.sandboxedruleruntimejava.registry;

/**
 * Tracks evaluations that are still running against a version, so a replaced version
 * is kept alive (and resources not reclaimed) until all in-flight calls finish.
 */
public interface InFlightTracker {
    void acquired(RuleVersion version);
    void released(RuleVersion version);
    int inFlight(String ruleId, int version);
}
