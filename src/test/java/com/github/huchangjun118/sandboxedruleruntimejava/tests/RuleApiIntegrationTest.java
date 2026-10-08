package com.github.huchangjun118.sandboxedruleruntimejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP coverage of version semantics and the distinguishable
 * failure mapping (requirements 1, 2, 3, 4).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuleApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void publishExecuteRollbackViaHttp() throws Exception {
        mvc.perform(post("/api/rules/pricing/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "source": "return input.amount > 100;"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(post("/api/rules/pricing/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input": {"amount": 250}, "seed": 7}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deterministic").value(true));

        mvc.perform(post("/api/rules/pricing/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 2, "source": "return false;"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post("/api/rules/pricing/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input": {"amount": 250}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.result").value(false));

        mvc.perform(post("/api/rules/pricing/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/rules/pricing/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(1))
                .andExpect(jsonPath("$[1]").value(2));
    }

    @Test
    void sandboxViolationIsForbiddenWithDistinctReason() throws Exception {
        mvc.perform(post("/api/rules/evil/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "source": "return readFile(\\"/etc/passwd\\");"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SANDBOX_FILESYSTEM"));

        mvc.perform(post("/api/rules/evil/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "source": "return fetch(\\"http://x\\");"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("SANDBOX_NETWORK"));
    }

    @Test
    void compileErrorIsBadRequest() throws Exception {
        mvc.perform(post("/api/rules/bad/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "source": "var = 1;"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("SCRIPT_COMPILE_ERROR"));
    }

    @Test
    void missingRuleIsNotFound() throws Exception {
        mvc.perform(post("/api/rules/ghost/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("RULE_NOT_FOUND"));
    }
}
