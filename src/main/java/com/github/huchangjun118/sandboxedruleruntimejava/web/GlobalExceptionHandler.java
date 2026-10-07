package com.github.huchangjun118.sandboxedruleruntimejava.web;

import com.github.huchangjun118.sandboxedruleruntimejava.exception.ScriptExecutionException;
import com.github.huchangjun118.sandboxedruleruntimejava.registry.VersionConflictException;
import com.github.huchangjun118.sandboxedruleruntimejava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures to distinct HTTP status codes while keeping error codes stable
 * for clients: unknown rule / version conflict -> 409/404, sandbox aborts -> 422 with a
 * machine-readable {@code kind} (and {@code denyReason} for capability denials).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(VersionConflictException e) {
        HttpStatus status = e.getMessage() != null && e.getMessage().startsWith("unknown rule")
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(new ErrorResponse("version_conflict", null, null, e.getMessage()));
    }

    @ExceptionHandler(ScriptExecutionException.class)
    public ResponseEntity<ErrorResponse> execution(ScriptExecutionException e) {
        HttpStatus status = switch (e.getKind()) {
            case REJECTED -> HttpStatus.SERVICE_UNAVAILABLE;
            case COMPILE_ERROR -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        String deny = e.getDenyReason() == null ? null : e.getDenyReason().name();
        return ResponseEntity.status(status)
                .body(new ErrorResponse("script_execution", e.getKind().name(),
                        deny, e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> badBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("bad_request", null, null, "malformed request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> internal(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal", null, null, e.toString()));
    }
}
