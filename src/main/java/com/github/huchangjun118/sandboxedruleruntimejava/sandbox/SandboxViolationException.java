package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Raised before execution starts when a script requests a capability the
 * sandbox denies (file system, process, network, reflection/runtime, ...).
 *
 * <p>The {@link #reasonCode()} values make the rejection reason
 * distinguishable: {@code SANDBOX_FILESYSTEM}, {@code SANDBOX_PROCESS},
 * {@code SANDBOX_NETWORK}, {@code SANDBOX_REFLECTION}, {@code SANDBOX_DENIED}.</p>
 */
public class SandboxViolationException extends RuleException {

    public static final String FILESYSTEM = "SANDBOX_FILESYSTEM";
    public static final String PROCESS = "SANDBOX_PROCESS";
    public static final String NETWORK = "SANDBOX_NETWORK";
    public static final String REFLECTION = "SANDBOX_REFLECTION";
    public static final String DENIED = "SANDBOX_DENIED";

    private final String reasonCode;

    public SandboxViolationException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    @Override
    public String reasonCode() {
        return reasonCode;
    }
}
