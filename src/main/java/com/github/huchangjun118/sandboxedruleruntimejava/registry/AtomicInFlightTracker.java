package com.github.huchangjun118.sandboxedruleruntimejava.registry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts evaluations in flight per rule version. The runtime acquires a version before
 * dispatching and releases it in a finally block, allowing replaced versions to be
 * retained until the last old-version call completes.
 */
public final class AtomicInFlightTracker implements InFlightTracker {

    private final ConcurrentMap<Key, AtomicInteger> counts = new ConcurrentHashMap<>();

    private record Key(String ruleId, int version) { }

    @Override
    public void acquired(RuleVersion version) {
        counts.computeIfAbsent(new Key(version.getRuleId(), version.getVersion()),
                k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void released(RuleVersion version) {
        Key key = new Key(version.getRuleId(), version.getVersion());
        AtomicInteger counter = counts.get(key);
        if (counter != null && counter.decrementAndGet() <= 0) {
            counts.remove(key, counter);
        }
    }

    @Override
    public int inFlight(String ruleId, int version) {
        AtomicInteger counter = counts.get(new Key(ruleId, version));
        return counter == null ? 0 : counter.get();
    }
}
