package com.jotheca.circulation.web;

import com.jotheca.circulation.dto.NotificationDto;
import com.jotheca.circulation.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import java.util.Map;

/** In-app notification feed (v1). Email mirrors go out automatically; push arrives later. */
@RestController
@RequestMapping("/api/v1/tenant/notifications")
public class TenantNotificationController {

    private final NotificationService notifications;

    public TenantNotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/me")
    public Page<NotificationDto> mine(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "30") int size) {
        return notifications.mine(PageRequest.of(Math.min(page, 500), Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/me/unread-count")
    public Map<String, Long> unread() {
        return Map.of("unread", notifications.unreadCount());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable String id) {
        notifications.markRead(id);
    }

    @PostMapping("/me/read-all")
    public Map<String, Integer> markAll() {
        return Map.of("marked", notifications.markAllRead());
    }
}
