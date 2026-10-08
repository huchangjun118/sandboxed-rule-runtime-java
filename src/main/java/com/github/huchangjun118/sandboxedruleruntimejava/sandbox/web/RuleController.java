package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.web;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ExecutionResult;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.RuleExecutionService;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP surface for publishing, rolling back and executing rule scripts.
 *
 * <p>Publish fully validates and sandbox-scans before committing; executing
 * snapshots the current release so concurrent hot replacement never loses or
 * double-judges a request.</p>
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleExecutionService service;

    public RuleController(RuleExecutionService service) {
        this.service = service;
    }

    /** Publish (commit) a new version. Body: {@code {"version": 3, "source": "..."}}. */
    @PostMapping("/{ruleId}/versions")
    public VersionView publish(@PathVariable String ruleId,
                               @RequestBody PublishRequest request) {
        if (request == null || request.source() == null || request.source().isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        ScriptVersion v = service.publish(ruleId, request.version(), request.source());
        return VersionView.of(v);
    }

    /** Roll back the current pointer to an existing committed version. */
    @PostMapping("/{ruleId}/rollback")
    public VersionView rollback(@PathVariable String ruleId,
                                @RequestBody RollbackRequest request) {
        if (request == null || request.version() <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        return VersionView.of(service.rollback(ruleId, request.version()));
    }

    /** Execute the currently committed release against one immutable input. */
    @PostMapping("/{ruleId}/execute")
    public ExecutionResult execute(@PathVariable String ruleId,
                                   @RequestBody(required = false) ExecuteRequest request) {
        Object input = request == null ? null : request.input();
        Long seed = request == null ? null : request.seed();
        return service.execute(ruleId, input, seed);
    }

    @GetMapping("/{ruleId}")
    public VersionView current(@PathVariable String ruleId) {
        return VersionView.of(service.current(ruleId)
                .orElseThrow(() -> new com.github.huchangjun118.sandboxedruleruntimejava
                        .sandbox.RuleNotFoundException(ruleId)));
    }

    @GetMapping("/{ruleId}/versions")
    public List<Long> versions(@PathVariable String ruleId) {
        return service.versions(ruleId);
    }
}
