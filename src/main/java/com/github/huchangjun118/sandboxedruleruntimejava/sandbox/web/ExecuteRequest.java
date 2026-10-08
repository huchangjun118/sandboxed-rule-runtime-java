package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.web;

import java.util.Map;

/**
 * Execute request body: {@code input} is an immutable per-request snapshot;
 * {@code seed} fixes the deterministic RNG (a fixed seed is derived when null).
 */
public record ExecuteRequest(Object input, Long seed) {
}
