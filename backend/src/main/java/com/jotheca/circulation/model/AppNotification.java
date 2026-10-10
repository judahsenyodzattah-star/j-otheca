package com.jotheca.circulation.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** In-app notification (v1, spec §29). The same event is mirrored to email via MailService. */
@Entity
@Table(name = "notifications")
public class AppNotification extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** Machine type, e.g. RESERVATION_READY, LOAN_ISSUED, FINE_ASSESSED. */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
}
