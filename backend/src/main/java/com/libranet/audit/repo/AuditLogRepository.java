package com.libranet.audit.repo;

import com.libranet.audit.model.AuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository
        extends JpaRepository<AuditLog, String>, JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<AuditLog> findByActorEmailOrderByCreatedAtDesc(String actorEmail);
    /** Platform dashboard: recent event volume. */
    long countByCreatedAtAfter(java.time.Instant after);

}
