package com.jotheca.media.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Metadata for one uploaded image (spec: media at rest lives in object storage; this row is the
 * database side of it, tenant-scoped). Bytes sit under the tenant-prefixed {@code objectKey};
 * deletion flips {@link MediaStatus} to DELETED without destroying anything.
 */
@Entity
@Table(name = "media_objects")
public class MediaObject extends TenantScopedEntity {

    public enum MediaStatus { ACTIVE, DELETED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaKind kind;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "thumb_key", length = 500)
    private String thumbKey;

    /** Sniffed from magic bytes — the client-supplied content type is never trusted. */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** full + thumbnail bytes; the storage-quota hard-check sums this column. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    private Integer width;
    private Integer height;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MediaStatus status = MediaStatus.ACTIVE;

    public MediaKind getKind() { return kind; }
    public void setKind(MediaKind kind) { this.kind = kind; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getThumbKey() { return thumbKey; }
    public void setThumbKey(String thumbKey) { this.thumbKey = thumbKey; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public MediaStatus getStatus() { return status; }
    public void setStatus(MediaStatus status) { this.status = status; }
}
