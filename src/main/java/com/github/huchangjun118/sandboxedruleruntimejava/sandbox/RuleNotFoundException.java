package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

/**
 * Raised when a rule id has no committed version.
 */
public class RuleNotFoundException extends RuleException {

    public RuleNotFoundException(String ruleId) {
        super("no committed version for rule: " + ruleId);
    }

    @Override
    public String reasonCode() {
        return "RULE_NOT_FOUND";
    }
}
