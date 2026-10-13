package com.jotheca.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Brute-force protection: the 6th login attempt in a minute gets a 429. */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=5")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RateLimitTest {

    @Autowired MockMvc mvc;

    @Test
    void loginIsRateLimitedAfterConfiguredAttempts() throws Exception {
        String body = "{\"email\":\"nobody@example.com\",\"password\":\"some-password\"}";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
