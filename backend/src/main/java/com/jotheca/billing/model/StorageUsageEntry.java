package com.jotheca.billing.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Append-only storage ledger (Phase 9). A tenant's usage is SUM(deltaBytes) — positive on
 * upload, negative when media is retired/replaced/deleted. Rows are never updated.
 */
@Entity
@Table(name = "storage_usage")
public class StorageUsageEntry extends TenantScopedEntity {

    public enum Reason { MEDIA_UPLOAD, MEDIA_RETIRE, MEDIA_DELETE }

    @Column(name = "delta_bytes", nullable = false)
    private long deltaBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason;

    @Column(name = "media_object_id", length = 36)
    private String mediaObjectId;

    @Column(length = 300)
    private String note;

    public long getDeltaBytes() { return deltaBytes; }
    public void setDeltaBytes(long deltaBytes) { this.deltaBytes = deltaBytes; }
    public Reason getReason() { return reason; }
    public void setReason(Reason reason) { this.reason = reason; }
    public String getMediaObjectId() { return mediaObjectId; }
    public void setMediaObjectId(String mediaObjectId) { this.mediaObjectId = mediaObjectId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
