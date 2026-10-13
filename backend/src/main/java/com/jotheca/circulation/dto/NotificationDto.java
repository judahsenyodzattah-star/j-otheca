package com.jotheca.circulation.dto;

import com.jotheca.circulation.model.AppNotification;

public record NotificationDto(String id, String type, String title, String body, boolean read, String createdAt) {

    public static NotificationDto from(AppNotification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getBody(),
                n.getReadAt() != null, n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
    }
}
