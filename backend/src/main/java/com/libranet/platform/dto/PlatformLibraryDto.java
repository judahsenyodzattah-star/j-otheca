package com.libranet.platform.dto;

import com.libranet.tenancy.model.Library;
import java.time.Instant;

/** Platform-plane view of a tenant: includes internal uid (SUPER_ADMIN only). */
public record PlatformLibraryDto(String id, String uid, String slug, String name, String type,
                                 String status, String joinPolicy, boolean visibility,
                                 String city, String country, int memberCount, Instant createdAt) {

    public static PlatformLibraryDto from(Library l) {
        return new PlatformLibraryDto(l.getId(), l.getUid(), l.getSlug(), l.getName(), l.getType().name(),
                l.getStatus().name(), l.getJoinPolicy().name(), l.isVisibility(),
                l.getCity(), l.getCountry(), l.getMemberCount(), l.getCreatedAt());
    }
}
