package com.github.huchangjun118.sandboxedruleruntimejava.web;

import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.model.EvaluationResult;
import com.github.huchangjun118.sandboxedruleruntimejava.model.ResourceQuota;
import com.github.huchangjun118.sandboxedruleruntimejava.runtime.DefaultRuleRuntime;
import com.github.huchangjun118.sandboxedruleruntimejava.runtime.RuleRuntime;
import com.github.huchangjun118.sandboxedruleruntimejava.web.dto.EvaluateRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.web.dto.PublishRequest;
import com.github.huchangjun118.sandboxedruleruntimejava.web.dto.RollbackRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP API for the sandboxed rule runtime.
 *
 * <ul>
 *   <li>{@code POST /api/rules/{id}/publish} — atomically publish a new version.</li>
 *   <li>{@code GET  /api/rules/{id}} — current committed version metadata.</li>
 *   <li>{@code GET  /api/rules/{id}/history} — committed version trail.</li>
 *   <li>{@code POST /api/rules/{id}/evaluate} — run the current version once.</li>
 *   <li>{@code POST /api/rules/{id}/rollback} — atomically restore a prior version.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final DefaultRuleRuntime runtime;

    public RuleController(RuleRuntime runtime) {
        this.runtime = (DefaultRuleRuntime) runtime;
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable("id") String id,
                                                       @RequestBody PublishRequest body) {
        ResourceQuota quota = runtime.defaultQuota();
        ResourceQuota requested = new ResourceQuota(
                body.getTimeoutMillis() == null ? quota.getTimeoutMillis() : body.getTimeoutMillis(),
                body.getMemoryBytes() == null ? quota.getMemoryBytes() : body.getMemoryBytes(),
                body.getOutputBytes() == null ? quota.getOutputBytes() : body.getOutputBytes(),
                body.getRecursionDepth() == null ? quota.getMaxRecursionDepth() : body.getRecursionDepth(),
                body.getStatementLimit() == null ? quota.getStatementLimit() : body.getStatementLimit());
        runtime.publish(id, body.getVersion(), body.getSource(), requested);
        return ResponseEntity.ok(versionView(id, runtime.currentVersion(id)));
    }

    @GetMapping("/{id}")
    public Map<String, Object> current(@PathVariable("id") String id) {
        int version = runtime.currentVersion(id);
        if (version < 0) {
            return Map.of("ruleId", id, "currentVersion", -1, "exists", false);
        }
        return versionView(id, version);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable("id") String id) {
        return runtime.history(id).stream()
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", v.getVersion());
                    m.put("quota", quotaView(v.getQuota()));
                    return m;
                })
                .toList();
    }

    @PostMapping("/{id}/evaluate")
    public EvaluationResult evaluate(@PathVariable("id") String id,
                                     @RequestBody EvaluateRequest body) {
        long seed = body.getSeed() == null ? 0L : body.getSeed();
        EvaluationRequest request = new EvaluationRequest(
                id, body.getInput(), body.getContext(), seed);
        return runtime.evaluate(request);
    }

    @PostMapping("/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable("id") String id,
                                        @RequestBody RollbackRequest body) {
        runtime.rollback(id, body.getVersion());
        return versionView(id, runtime.currentVersion(id));
    }

    private Map<String, Object> versionView(String id, int version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", id);
        m.put("currentVersion", version);
        m.put("exists", true);
        m.put("inFlight", runtime.inFlight(id, version));
        return m;
    }

    private Map<String, Object> quotaView(ResourceQuota q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timeoutMillis", q.getTimeoutMillis());
        m.put("memoryBytes", q.getMemoryBytes());
        m.put("outputBytes", q.getOutputBytes());
        m.put("maxRecursionDepth", q.getMaxRecursionDepth());
        m.put("statementLimit", q.getStatementLimit());
        return m;
    }
}
