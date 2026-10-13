package com.jotheca.media.repo;

import com.jotheca.media.model.ReviewReport;
import com.jotheca.media.model.ReviewReport.ReportStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, String> {

    Optional<ReviewReport> findByTenantIdAndReviewIdAndReporterMembershipId(
            String tenantId, String reviewId, String reporterMembershipId);

    List<ReviewReport> findByTenantIdAndReviewIdAndStatus(String tenantId, String reviewId, ReportStatus status);

    long countByTenantIdAndReviewIdAndStatus(String tenantId, String reviewId, ReportStatus status);
    /** Tenant dashboard: open report load across all reviews. */
    long countByTenantIdAndStatus(String tenantId, ReportStatus status);

}
