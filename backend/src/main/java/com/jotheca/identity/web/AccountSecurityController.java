package com.jotheca.identity.web;

import com.jotheca.identity.dto.SecurityDtos.MfaCodeRequest;
import com.jotheca.identity.dto.SecurityDtos.MfaDisableRequest;
import com.jotheca.identity.dto.SecurityDtos.MfaEnrollResponse;
import com.jotheca.identity.dto.SecurityDtos.MfaStatusResponse;
import com.jotheca.identity.dto.SecurityDtos.SessionDto;
import com.jotheca.identity.service.MfaService;
import com.jotheca.identity.service.SessionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Account-plane security (Phase 8, no tenant context): sessions/devices + TOTP MFA management. */
@RestController
@RequestMapping("/api/v1/me/security")
public class AccountSecurityController {

    private final SessionService sessions;
    private final MfaService mfa;

    public AccountSecurityController(SessionService sessions, MfaService mfa) {
        this.sessions = sessions;
        this.mfa = mfa;
    }

    // ---------- sessions / devices ----------

    @GetMapping("/sessions")
    public List<SessionDto> mySessions(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return sessions.listMine(authorization);
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable String id) {
        sessions.revokeMine(id);
    }

    /** Sign out everywhere (revokes every session incl. this device). */
    @PostMapping("/sessions/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAll() {
        sessions.revokeAllMine();
    }

    // ---------- MFA (TOTP) ----------

    @GetMapping("/mfa")
    public MfaStatusResponse mfaStatus() {
        return mfa.status();
    }

    @PostMapping("/mfa/enroll")
    public MfaEnrollResponse enroll() {
        return mfa.enroll();
    }

    @PostMapping("/mfa/enable")
    public MfaStatusResponse enable(@Valid @RequestBody MfaCodeRequest req) {
        return mfa.enable(req.code());
    }

    @PostMapping("/mfa/disable")
    public MfaStatusResponse disable(@Valid @RequestBody MfaDisableRequest req) {
        return mfa.disable(req.password(), req.code());
    }
}
