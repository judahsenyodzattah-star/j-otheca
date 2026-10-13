package com.jotheca.media.dto;

import com.jotheca.media.model.MediaObject;
import java.time.Instant;

public final class MediaDtos {

    private MediaDtos() {}

    /** Metadata for one stored image. `url`/`thumbUrl` are API paths the caller prefixes with its base URL. */
    public record MediaDto(String id, String kind, String contentType, long sizeBytes,
                           Integer width, Integer height, String url, String thumbUrl, Instant createdAt) {

        public static MediaDto of(MediaObject m, String urlBase) {
            return new MediaDto(m.getId(), m.getKind().name(), m.getContentType(), m.getSizeBytes(),
                    m.getWidth(), m.getHeight(),
                    urlBase + "/" + m.getId(),
                    m.getThumbKey() != null ? urlBase + "/" + m.getId() + "?variant=thumb" : null,
                    m.getCreatedAt());
        }
    }
}
