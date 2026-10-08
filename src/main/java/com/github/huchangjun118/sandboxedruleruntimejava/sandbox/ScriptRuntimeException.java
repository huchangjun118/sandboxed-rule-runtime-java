package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Raised for ordinary runtime faults inside a script (undefined variable,
 * type mismatch, division by zero, ...). Such faults never kill the host
 * worker thread: they are captured and reported per execution.
 */
public class ScriptRuntimeException extends RuleException {

    public ScriptRuntimeException(String message) {
        super(message);
    }

    public ScriptRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String reasonCode() {
        return "SCRIPT_RUNTIME_ERROR";
    }
}
