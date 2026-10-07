package com.github.huchangjun118.sandboxedruleruntimejava.runtime;

import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationResult;
import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;

/**
 * Facade combining version management and sandboxed execution.
 */
public interface RuleRuntime {
    void publish(String ruleId, int version, String source, ResourceQuota quota);
    EvaluationResult evaluate(EvaluationRequest request);
    void rollback(String ruleId, int targetVersion);
    int currentVersion(String ruleId);
    void shutdown();
}
