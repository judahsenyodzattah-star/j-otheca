package com.libranet.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

public final class SecurityDtos {

    private SecurityDtos() {}

    // ---------- sessions / devices ----------

    public record SessionDto(String id, String deviceInfo, String ip,
                             Instant createdAt, Instant lastUsedAt, boolean current) {}

    // ---------- MFA (TOTP) ----------

    public record MfaStatusResponse(boolean mfaEnabled) {}

    /** Shown ONCE at enrolment: the secret URI to render as a QR code in an authenticator app. */
    public record MfaEnrollResponse(String secret, String otpauthUri) {}

    public record MfaCodeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    public record MfaDisableRequest(@NotBlank String password,
                                    @NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    public record MfaVerifyRequest(@NotBlank String mfaToken,
                                   @NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    /** Login response when the account has MFA on: exchange via POST /api/v1/auth/mfa/verify. */
    public record MfaChallengeResponse(boolean mfaRequired, String mfaToken, int expiresInSeconds) {
        public static MfaChallengeResponse of(String token) {
            return new MfaChallengeResponse(true, token, 300);
        }
    }
}
