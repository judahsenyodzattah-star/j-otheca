package com.jotheca.billing.service;

import com.jotheca.billing.model.StorageUsageEntry;
import com.jotheca.billing.model.StorageUsageEntry.Reason;
import com.jotheca.billing.repo.StorageUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The storage_usage ledger (Phase 9). Called by the media module; deliberately has no media
 * dependency of its own so billing never imports media code (mirrors the payments port rule).
 */
@Service
public class StorageUsageService {

    private final StorageUsageRepository ledger;

    public StorageUsageService(StorageUsageRepository ledger) {
        this.ledger = ledger;
    }

    /** Append an entry. Propagates with the caller's transaction (upload and ledger commit together). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String tenantId, long deltaBytes, Reason reason, String mediaObjectId, String note) {
        StorageUsageEntry entry = new StorageUsageEntry();
        entry.setTenantId(tenantId);
        entry.setDeltaBytes(deltaBytes);
        entry.setReason(reason);
        entry.setMediaObjectId(mediaObjectId);
        entry.setNote(note == null ? null : note.substring(0, Math.min(note.length(), 300)));
        ledger.save(entry);
    }

    @Transactional(readOnly = true)
    public long usageOf(String tenantId) {
        return Math.max(0, ledger.usageOf(tenantId));
    }

    @Transactional(readOnly = true)
    public long totalUsage() {
        return Math.max(0, ledger.totalUsage());
    }
}
