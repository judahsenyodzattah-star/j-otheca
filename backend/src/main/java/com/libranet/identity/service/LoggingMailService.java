package com.libranet.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Dev profile mailer: prints tokens to the log (no SMTP needed). Never logs them in production. */
@Service
@Profile("dev")
public class LoggingMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailService.class);

    @Override
    public void sendEmailVerification(String to, String token) {
        log.info("[dev-mail] email-verification for {}: token={}", to, token);
    }

    @Override
    public void sendPasswordReset(String to, String token) {
        log.info("[dev-mail] password-reset for {}: token={}", to, token);
    }

    @Override
    public void sendNotification(String to, String subject, String body) {
        log.info("[dev-mail] notification for {}: {} — {}", to, subject, body);
    }
}
