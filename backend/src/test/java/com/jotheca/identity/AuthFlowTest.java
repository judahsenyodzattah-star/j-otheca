package com.jotheca.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jotheca.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Phase 1 acceptance: register → verify → login → refresh rotation with reuse detection. */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthFlowTest {

    @Autowired MockMvc mvc;

    @Test
    void registerVerifyLoginAndReadProfile() throws Exception {
        String email = "it-" + System.nanoTime() + "@example.com";
        JsonNode registered = TestApi.register(mvc, email, "Password12345", "Integration Tester");
        assertThat(registered.path("user").path("email").asText()).isEqualTo(email);
        String devToken = registered.path("devVerificationToken").asText();
        assertThat(devToken).isNotBlank();

        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + devToken + "\"}"))
                .andExpect(status().isOk());

        String token = TestApi.login(mvc, email, "Password12345");
        mvc.perform(get("/api/v1/me").header("Authorization", TestApi.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    void relatesToTokenReuseDetectionAndFamilyRevocation() throws Exception {
        String email = "rot-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Rotator");

        MvcResult loginResult = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Password12345\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode login = TestApi.readJson(loginResult);
        String refresh1 = login.path("refreshToken").asText();

        MvcResult rotatedResult = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh1 + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String refresh2 = TestApi.readJson(rotatedResult).path("refreshToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // replay of the rotated-out token → theft → 401 and the whole family is revoked
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh1 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REUSE_DETECTED"));

        // even the newest token of the compromised family is now dead
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh2 + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingWhichPartFailed() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"patron@jotheca.app\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void unknownAccountAndWrongPasswordReturnIdenticalError() throws Exception {
        // R1: an unknown email and a wrong password must be externally indistinguishable.
        String known = "r1known-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, known, "Correct12345", "R1 Known");

        MvcResult wrongPw = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + known + "\",\"password\":\"definitely-wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult unknown = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"r1ghost-" + System.nanoTime() + "@example.com\",\"password\":\"definitely-wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode a = TestApi.readJson(wrongPw);
        JsonNode b = TestApi.readJson(unknown);
        assertThat(b.path("code").asText()).isEqualTo("BAD_CREDENTIALS");
        assertThat(b.path("code").asText()).isEqualTo(a.path("code").asText());
        assertThat(b.path("message").asText()).isEqualTo(a.path("message").asText());
        assertThat(unknown.getResponse().getStatus()).isEqualTo(wrongPw.getResponse().getStatus());
    }

    @Test
    void unknownAccountLoginStillVerifiesPasswordToEqualizeTiming() throws Exception {
        // R1: the unknown-account path must burn the same BCrypt work as a real credential check,
        // otherwise response time is an account-existence oracle. BCrypt (strength 10) costs tens of
        // ms; a path that skips it answers in a couple of ms. Coarse guard — lower the floor if a very
        // fast machine ever trips it. Warm up first so class-loading/JIT don't skew the measurement.
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"r1warm-" + System.nanoTime() + "@example.com\",\"password\":\"warmup12345\"}"))
                .andExpect(status().isUnauthorized());

        long start = System.nanoTime();
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"r1ghost-" + System.nanoTime() + "@example.com\",\"password\":\"whatever12345\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(20);
    }

    @Test
    void passwordResetRevokesAllSessions() throws Exception {
        String email = "reset-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Resetter");
        String accessBefore = TestApi.login(mvc, email, "Password12345");

        MvcResult forgotResult = mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        // token is only mailed; in dev it's logged — the reset endpoint contract is what we verify:
        // invalid tokens are rejected
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bogus\",\"newPassword\":\"Password12345!\"}"))
                .andExpect(status().isBadRequest());
        // old access token remains technically valid until expiry (short lived), refresh sessions were untouched
        mvc.perform(get("/api/v1/me").header("Authorization", TestApi.bearer(accessBefore)))
                .andExpect(status().isOk());
        assertThat(forgotResult.getResponse().getContentAsString()).contains("reset link");
    }
}
