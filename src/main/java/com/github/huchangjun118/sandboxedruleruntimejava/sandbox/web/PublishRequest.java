package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.web;

/**
 * Publish request body.
 *
 * @param version explicit, strictly increasing version number; {@code null}
 *                lets the service assign the next monotonic number
 * @param source  rule script source
 */
public record PublishRequest(Long version, String source) {
}
