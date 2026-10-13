package com.jotheca.identity.service;

public interface MailService {
    void sendEmailVerification(String to, String token);
    void sendPasswordReset(String to, String token);
    /** Transactional notices (reservation ready, fines…). Implementations must be failure-safe. */
    void sendNotification(String to, String subject, String body);
}
