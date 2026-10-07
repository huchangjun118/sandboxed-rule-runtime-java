package com.github.huchangjun118.sandboxedruleruntimejava.web.dto;

import java.util.Map;

/** REST body for evaluating the current version of a rule. */
public class EvaluateRequest {
    private Map<String, Object> input;
    private Map<String, Object> context;
    private Long seed;

    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> input) { this.input = input; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
}
