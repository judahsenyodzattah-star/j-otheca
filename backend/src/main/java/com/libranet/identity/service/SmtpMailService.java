package com.libranet.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Production mailer (Mailpit locally, real SMTP via MAIL_HOST/MAIL_PORT env in deployments). */
@Service
@Profile("!dev")
public class SmtpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailService.class);

    private final JavaMailSender sender;

    public SmtpMailService(JavaMailSender sender) {
        this.sender = sender;
    }

    @Override
    public void sendEmailVerification(String to, String token) {
        send(to, "Verify your LibraNet email",
                "Welcome to LibraNet. Verify your email with this link:\n\nhttps://libranet.app/verify-email?token=" + token);
    }

    @Override
    public void sendPasswordReset(String to, String token) {
        send(to, "Reset your LibraNet password",
                "A password reset was requested for your account. If this was you:\n\nhttps://libranet.app/reset-password?token=" + token
                        + "\n\nIf it wasn't you, ignore this email — nothing changes.");
    }

    @Override
    public void sendNotification(String to, String subject, String body) {
        send(to, subject, body);
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
        } catch (Exception e) {
            // Never leak tokens into logs; failures are tracked by event only.
            log.warn("mail send failed ({}): {}", subject, e.getMessage());
        }
    }
}
