package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end HTTP coverage of publish/evaluate/rollback and the stable error contract:
 * sandbox denials carry a machine-readable kind and a distinguishable denyReason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuleApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void publishEvaluateAndRollbackOverHttp() {
        String id = "http-rule-" + System.nanoTime();
        // publish v1
        ResponseEntity<Map> pub1 = rest.exchange(url("/api/rules/" + id + "/publish"),
                HttpMethod.POST, json(Map.of(
                        "version", 1,
                        "source", "function decide(input){ return {allowed: input.amount < 100, reason:'ok'}; }")),
                Map.class);
        assertEquals(HttpStatus.OK, pub1.getStatusCode());
        assertEquals(1, pub1.getBody().get("currentVersion"));

        // evaluate allowed=false for amount 150
        @SuppressWarnings("unchecked")
        Map<String, Object> evalBody = (Map<String, Object>) rest.exchange(
                url("/api/rules/" + id + "/evaluate"), HttpMethod.POST,
                json(Map.of("input", Map.of("amount", 150), "seed", 7)), Map.class).getBody();
        Map<String, Object> decision = (Map<String, Object>) evalBody.get("decision");
        assertEquals(false, decision.get("allowed"));
        assertEquals(1, evalBody.get("version"));

        // publish v2 then rollback
        rest.exchange(url("/api/rules/" + id + "/publish"), HttpMethod.POST,
                json(Map.of("version", 2,
                        "source", "function decide(){ return {allowed:true, reason:'v2'}; }")),
                Map.class);
        ResponseEntity<Map> rb = rest.exchange(url("/api/rules/" + id + "/rollback"),
                HttpMethod.POST, json(Map.of("version", 1)), Map.class);
        assertEquals(HttpStatus.OK, rb.getStatusCode());
        Object cur = rb.getBody().get("currentVersion");
        assertTrue(((Number) cur).intValue() >= 3);
    }

    @Test
    void sandboxDenialReportsDistinctReasonOverHttp() {
        String id = "http-deny-" + System.nanoTime();
        rest.exchange(url("/api/rules/" + id + "/publish"), HttpMethod.POST,
                json(Map.of("version", 1,
                        "source", "function decide(){ var F=Java.type('java.net.Socket'); return {allowed:false}; }")),
                Map.class);
        ResponseEntity<Map> resp = rest.exchange(url("/api/rules/" + id + "/evaluate"),
                HttpMethod.POST, json(Map.of("input", Map.of(), "seed", 1)), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertEquals("NETWORK", body.get("denyReason"));
        assertEquals("DENIED", body.get("kind"));
    }

    @Test
    void compileErrorIsBadRequestAndUnknownRuleIsNotFound() {
        String id = "http-bad-" + System.nanoTime();
        ResponseEntity<Map> bad = rest.exchange(url("/api/rules/" + id + "/publish"),
                HttpMethod.POST, json(Map.of("version", 1, "source", "function decide({ !!")),
                Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertEquals("COMPILE_ERROR", bad.getBody().get("kind"));

        ResponseEntity<Map> missing = rest.exchange(url("/api/rules/does-not-exist/evaluate"),
                HttpMethod.POST, json(Map.of("input", Map.of())), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test
    void determinismReflectedInRepeatedHttpResults() {
        String id = "http-det-" + System.nanoTime();
        rest.exchange(url("/api/rules/" + id + "/publish"), HttpMethod.POST,
                json(Map.of("version", 1,
                        "source", "function decide(){ var x=0; for(var i=0;i<500;i++){x+=Math.random();}"
                                + " return {allowed:true, reason:String(x)}; }")),
                Map.class);
        ResponseEntity<Map> a = rest.exchange(url("/api/rules/" + id + "/evaluate"),
                HttpMethod.POST, json(Map.of("seed", 99)), Map.class);
        ResponseEntity<Map> b = rest.exchange(url("/api/rules/" + id + "/evaluate"),
                HttpMethod.POST, json(Map.of("seed", 99)), Map.class);
        Map<String, Object> da = (Map<String, Object>) a.getBody().get("decision");
        Map<String, Object> db = (Map<String, Object>) b.getBody().get("decision");
        assertEquals(da.get("reason"), db.get("reason"));
    }
}
