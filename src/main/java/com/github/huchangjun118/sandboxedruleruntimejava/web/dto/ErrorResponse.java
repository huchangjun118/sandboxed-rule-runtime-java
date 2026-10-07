package com.github.huchangjun118.sandboxedruleruntimejava.web.dto;

/** Stable JSON error body. */
public record ErrorResponse(String error, String kind, String denyReason, String message) {
}
