package com.jotheca.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression for the CORS fix (audit D2). The security chain must honor the configured
 * {@code app.cors-allowed-origin-patterns} (env {@code CORS_ORIGINS}) rather than a
 * hard-coded {@code "*"}. This is the control the staging config and the pentest checklist
 * rely on ("{@code *} only in dev"). Against the previous wildcard wiring, the
 * {@link #unconfiguredOriginGetsNoAllowHeader()} case would fail (every origin allowed).
 */
@SpringBootTest(properties = "app.cors-allowed-origin-patterns=https://good.example")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CorsConfigTest {

    @Autowired MockMvc mvc;

    @Test
    void configuredOriginIsAllowedOnPreflight() throws Exception {
        mvc.perform(options("/api/v1/plans")
                        .header("Origin", "https://good.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://good.example"));
    }

    @Test
    void unconfiguredOriginGetsNoAllowHeader() throws Exception {
        mvc.perform(options("/api/v1/plans")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
