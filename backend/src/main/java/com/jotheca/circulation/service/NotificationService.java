package com.jotheca.circulation.service;

import com.jotheca.circulation.dto.NotificationDto;
import com.jotheca.circulation.model.AppNotification;
import com.jotheca.circulation.repo.NotificationRepository;
import com.jotheca.common.error.ApiException;
import com.jotheca.common.tenant.TenantContext;
import com.jotheca.common.tenant.TenantGuard;
import com.jotheca.common.web.SecurityUsers;
import com.jotheca.identity.repo.UserRepository;
import com.jotheca.identity.service.MailService;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Notifications v1 (spec §29): in-app feed + best-effort email mirror. Push lands in a later phase. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final MailService mail;

    public NotificationService(NotificationRepository notifications, UserRepository users, MailService mail) {
        this.notifications = notifications;
        this.users = users;
        this.mail = mail;
    }

    /** Fire-and-forget internal sender — never throws into the business transaction. */
    @Transactional
    public void notify(String tenantId, String userId, String type, String title, String body) {
        AppNotification n = new AppNotification();
        n.setTenantId(tenantId);
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        n.setBody(body != null && body.length() > 1000 ? body.substring(0, 1000) : body);
        notifications.save(n);
        users.findById(userId).ifPresent(u -> {
            try {
                mail.sendNotification(u.getEmail(), title, body == null ? "" : body);
            } catch (Exception e) {
                log.debug("email mirror for notification {} failed: {}", type, e.getMessage());
            }
        });
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> mine(Pageable pageable) {
        TenantContext.Current ctx = TenantContext.require();
        return notifications
                .findByTenantIdAndUserIdOrderByCreatedAtDesc(ctx.libraryId(), SecurityUsers.current().id(), pageable)
                .map(NotificationDto::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.countByTenantIdAndUserIdAndReadAtIsNull(
                TenantContext.requireLibraryId(), SecurityUsers.current().id());
    }

    @Transactional
    public void markRead(String notificationId) {
        AppNotification n = notifications.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found"));
        TenantGuard.requireBelongsToCurrentTenant(n.getTenantId());
        if (!n.getUserId().equals(SecurityUsers.current().id())) {
            throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found"); // others' feeds hidden
        }
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            notifications.save(n);
        }
    }

    @Transactional
    public int markAllRead() {
        List<AppNotification> unread = notifications.findByTenantIdAndUserIdAndReadAtIsNull(
                TenantContext.requireLibraryId(), SecurityUsers.current().id());
        unread.forEach(n -> n.setReadAt(Instant.now()));
        notifications.saveAll(unread);
        return unread.size();
    }
}
