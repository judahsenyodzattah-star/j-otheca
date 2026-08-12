package com.libranet.billing.repo;

import com.libranet.billing.model.StorageUsageEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorageUsageRepository extends JpaRepository<StorageUsageEntry, String> {

    /** Current usage of one tenant from the ledger (never negative). */
    @Query("select coalesce(sum(e.deltaBytes), 0) from StorageUsageEntry e where e.tenantId = :tenantId")
    long usageOf(@Param("tenantId") String tenantId);

    /** Platform-wide storage footprint (super-admin dashboard). */
    @Query("select coalesce(sum(e.deltaBytes), 0) from StorageUsageEntry e")
    long totalUsage();
}
