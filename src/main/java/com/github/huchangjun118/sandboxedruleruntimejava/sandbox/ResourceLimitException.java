package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Raised when a single execution exceeds one of its quotas: wall-clock time,
 * memory (allocation accounting), output size, recursion depth or instruction
 * steps. The reason code states which quota was hit.
 */
public class ResourceLimitException extends RuleException {

    public static final String TIME = "RESOURCE_TIME_LIMIT";
    public static final String MEMORY = "RESOURCE_MEMORY_LIMIT";
    public static final String OUTPUT = "RESOURCE_OUTPUT_LIMIT";
    public static final String RECURSION = "RESOURCE_RECURSION_LIMIT";
    public static final String STEPS = "RESOURCE_STEP_LIMIT";

    private final String reasonCode;

    public ResourceLimitException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    @Override
    public String reasonCode() {
        return reasonCode;
    }
}
