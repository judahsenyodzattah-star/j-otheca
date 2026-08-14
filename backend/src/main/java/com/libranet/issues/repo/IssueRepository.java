package com.libranet.issues.repo;

import com.libranet.issues.model.Issue;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssueRepository extends JpaRepository<Issue, String> {

    @Query("SELECT COALESCE(MAX(i.issueNumber), 0) FROM Issue i WHERE i.tenantId = :tenantId")
    int maxIssueNumber(@Param("tenantId") String tenantId);

    Page<Issue> findByTenantIdAndReporterUserId(String tenantId, String reporterUserId, Pageable pageable);

    Optional<Issue> findByTenantIdAndReporterUserIdAndClientKey(String tenantId, String reporterUserId,
                                                                String clientKey);

    long countByTenantIdAndReporterUserIdAndCreatedAtAfter(String tenantId, String reporterUserId, Instant after);

    long countByTenantIdAndStatus(String tenantId, String status);

    /** Staff inbox, unassigned-only view. */
    @Query("SELECT i FROM Issue i WHERE i.tenantId = :tenantId AND i.assigneeMembershipId IS NULL"
            + " AND (:status IS NULL OR i.status = :status)"
            + " AND (:category IS NULL OR i.category = :category)")
    Page<Issue> inboxUnassigned(@Param("tenantId") String tenantId,
                                @Param("status") String status,
                                @Param("category") String category,
                                Pageable pageable);

    /** Staff inbox with optional filters — null params are ignored. */
    @Query("SELECT i FROM Issue i WHERE i.tenantId = :tenantId"
            + " AND (:status IS NULL OR i.status = :status)"
            + " AND (:category IS NULL OR i.category = :category)"
            + " AND (:assignee IS NULL OR i.assigneeMembershipId = :assignee)")
    Page<Issue> inbox(@Param("tenantId") String tenantId,
                      @Param("status") String status,
                      @Param("category") String category,
                      @Param("assignee") String assignee,
                      Pageable pageable);
}
