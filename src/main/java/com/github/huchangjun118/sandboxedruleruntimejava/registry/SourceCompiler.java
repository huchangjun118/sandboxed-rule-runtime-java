package com.github.huchangjun118.sandboxedruleruntimejava.registry;

/**
 * Prepares (parses/compiles) script source into a reusable handle <em>before</em> a
 * version is committed. Implementations must be side-effect free with respect to the
 * registry: a compilation failure must never alter the currently committed version.
 */
@FunctionalInterface
public interface SourceCompiler {
    /**
     * @return an opaque compiled handle (e.g. a Graal {@code Source})
     * @throws RuntimeException if the source cannot be compiled
     */
    Object compile(String ruleId, int version, String source);
}
