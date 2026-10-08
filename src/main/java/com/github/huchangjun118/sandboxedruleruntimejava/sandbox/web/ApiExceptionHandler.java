package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.web;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.OverloadException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ResourceLimitException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleNotFoundException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.SandboxViolationException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptCompileException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps runtime failures to HTTP responses while always preserving the stable
 * reason code, so clients can distinguish sandbox policy rejections, script
 * defects, missing versions and resource exhaustion.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Denied capabilities were requested before execution - forbidden. */
    @ExceptionHandler(SandboxViolationException.class)
    public ResponseEntity<ErrorView> sandbox(SandboxViolationException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorView(ex.reasonCode(), ex.getMessage()));
    }

    /** The script source itself is invalid - bad request. */
    @ExceptionHandler(ScriptCompileException.class)
    public ResponseEntity<ErrorView> compile(ScriptCompileException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorView(ex.reasonCode(), ex.getMessage()));
    }

    /** The script ran but misbehaved (type error, division by zero, ...). */
    @ExceptionHandler(ScriptRuntimeException.class)
    public ResponseEntity<ErrorView> runtime(ScriptRuntimeException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorView(ex.reasonCode(), ex.getMessage()));
    }

    /** Time/memory/output/recursion/step quota exceeded - the resource is exhausted. */
    @ExceptionHandler(ResourceLimitException.class)
    public ResponseEntity<ErrorView> limit(ResourceLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorView(ex.reasonCode(), ex.getMessage()));
    }

    /** The shared pool is saturated - service unavailable. */
    @ExceptionHandler(OverloadException.class)
    public ResponseEntity<ErrorView> overload(OverloadException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorView(ex.reasonCode(), ex.getMessage()));
    }

    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<ErrorView> notFound(RuleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorView(ex.reasonCode(), ex.getMessage()));
    }

    /** Invalid API arguments (blank source, bad version number, ...). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> badArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorView("INVALID_ARGUMENT", ex.getMessage()));
    }

    /** Fallback so a {@link RuleException} subtype never leaks as a 500. */
    @ExceptionHandler(RuleException.class)
    public ResponseEntity<ErrorView> rule(RuleException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorView(ex.reasonCode(), ex.getMessage()));
    }
}
