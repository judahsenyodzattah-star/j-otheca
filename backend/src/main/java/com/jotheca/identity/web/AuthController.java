package com.jotheca.identity.web;

import com.jotheca.identity.dto.ForgotPasswordRequest;
import com.jotheca.identity.dto.LoginRequest;
import com.jotheca.identity.dto.LogoutRequest;
import com.jotheca.identity.dto.MessageResponse;
import com.jotheca.identity.dto.RefreshRequest;
import com.jotheca.identity.dto.RegisterRequest;
import com.jotheca.identity.dto.RegisterResponse;
import com.jotheca.identity.dto.ResetPasswordRequest;
import com.jotheca.identity.dto.SecurityDtos.MfaVerifyRequest;
import com.jotheca.identity.dto.TokenResponse;
import com.jotheca.identity.dto.VerifyEmailRequest;
import com.jotheca.identity.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    /** Returns tokens, or an MFA challenge ({@code mfaRequired:true}) when the account has TOTP on. */
    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /** Second factor: exchange the login challenge token + 6-digit code for real tokens. */
    @PostMapping("/mfa/verify")
    public TokenResponse mfaVerify(@Valid @RequestBody MfaVerifyRequest req) {
        return authService.mfaVerify(req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest req) {
        authService.logout(req.refreshToken());
    }

    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        authService.verifyEmail(req);
        return new MessageResponse("Email verified");
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req);
        return new MessageResponse("If that email has an account, a reset link is on its way");
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
        return new MessageResponse("Password updated. Please sign in again.");
    }
}
