package com.github.huchangjun118.sandboxedruleruntimejava.runtime;

import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationResult;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.RuleVersion;

/**
 * Runs one version of a rule against one request inside a fresh, fully locked-down
 * context, enforcing all quotas. Implementations must guarantee isolation: state from
 * one execution (globals, counters, output buffers) never leaks into another.
 */
public interface SandboxEngine {
    EvaluationResult evaluate(RuleVersion version, EvaluationRequest request) throws Exception;
}
