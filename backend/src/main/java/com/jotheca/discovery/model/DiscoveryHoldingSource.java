package com.jotheca.discovery.model;

import com.jotheca.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Private reconciliation mapping; never expose this entity through a public DTO. */
@Entity
@Table(name = "holding_sources", schema = "discovery")
public class DiscoveryHoldingSource extends BaseEntity {

    @Column(name = "holding_id", nullable = false, length = 36)
    private String holdingId;
    @Column(name = "edition_id", nullable = false, length = 36)
    private String editionId;
    @Column(name = "source_library_id", nullable = false, length = 36)
    private String sourceLibraryId;
    @Column(name = "source_book_id", nullable = false, length = 36)
    private String sourceBookId;
    @Column(name = "source_version", nullable = false)
    private long sourceVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_state", nullable = false, length = 20)
    private DiscoverySyncState syncState = DiscoverySyncState.PENDING;
    @Column(name = "conflict_reason", length = 500)
    private String conflictReason;
    @Column(name = "projection_version", nullable = false)
    private long projectionVersion;

    public String getHoldingId() { return holdingId; }
    public void setHoldingId(String value) { holdingId = value; }
    public String getEditionId() { return editionId; }
    public void setEditionId(String value) { editionId = value; }
    public String getSourceLibraryId() { return sourceLibraryId; }
    public void setSourceLibraryId(String value) { sourceLibraryId = value; }
    public String getSourceBookId() { return sourceBookId; }
    public void setSourceBookId(String value) { sourceBookId = value; }
    public long getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(long value) { sourceVersion = value; }
    public DiscoverySyncState getSyncState() { return syncState; }
    public void setSyncState(DiscoverySyncState value) { syncState = value; }
    public String getConflictReason() { return conflictReason; }
    public void setConflictReason(String value) { conflictReason = value; }
    public long getProjectionVersion() { return projectionVersion; }
    public void setProjectionVersion(long value) { projectionVersion = value; }
}
