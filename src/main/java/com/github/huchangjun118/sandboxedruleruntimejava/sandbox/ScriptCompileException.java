package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Raised when script source cannot be tokenized or parsed.
 */
public class ScriptCompileException extends RuleException {

    private final int line;

    public ScriptCompileException(String message, int line) {
        super("compile error at line " + line + ": " + message);
        this.line = line;
    }

    public int getLine() {
        return line;
    }

    @Override
    public String reasonCode() {
        return "SCRIPT_COMPILE_ERROR";
    }
}
