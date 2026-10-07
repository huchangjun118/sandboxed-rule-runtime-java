package com.github.huchangjun118.sandboxedruleruntimejava.model;

import java.util.Map;

/**
 * Input to a rule evaluation: the target rule id, the payload exposed to the script as
 * {@code input}, optional evaluation context ({@code ctx}), and the deterministic seed.
 */
public final class EvaluationRequest {
    private final String ruleId;
    private final Map<String, Object> input;
    private final Map<String, Object> context;
    private final long seed;

    public EvaluationRequest(String ruleId, Map<String, Object> input,
                             Map<String, Object> context, long seed) {
        this.ruleId = ruleId;
        this.input = input == null ? Map.of() : Map.copyOf(input);
        this.context = context == null ? Map.of() : Map.copyOf(context);
        this.seed = seed;
    }

    public String getRuleId() { return ruleId; }
    public Map<String, Object> getInput() { return input; }
    public Map<String, Object> getContext() { return context; }
    public long getSeed() { return seed; }
}
