package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.web;

/** Stable machine readable error body: reason code plus human readable detail. */
public record ErrorView(String reason, String message) {
}
