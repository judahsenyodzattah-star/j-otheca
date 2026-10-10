package com.jotheca.circulation.repo;

import com.jotheca.circulation.model.Loan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanRepository extends JpaRepository<Loan, String> {

    Optional<Loan> findByTenantIdAndCopyIdAndStatus(String tenantId, String copyId, Loan.Status status);

    long countByTenantIdAndMembershipIdAndStatus(String tenantId, String membershipId, Loan.Status status);

    Page<Loan> findByTenantIdAndMembershipIdOrderByIssuedAtDesc(String tenantId, String membershipId,
                                                                Pageable pageable);

    Page<Loan> findByTenantIdOrderByIssuedAtDesc(String tenantId, Pageable pageable);

    List<Loan> findByTenantIdAndMembershipIdAndStatus(String tenantId, String membershipId, Loan.Status status);
    /** Tenant dashboard: loans by status. */
    long countByTenantIdAndStatus(String tenantId, Loan.Status status);

    /** Tenant dashboard: overdue = ACTIVE past due date. */
    long countByTenantIdAndStatusAndDueAtBefore(String tenantId, Loan.Status status, Instant dueAt);

    /** Lazy overdue-reminder sweep: ACTIVE loans past due that have not been reminded yet. */
    List<Loan> findByTenantIdAndStatusAndDueAtBeforeAndReminderSentAtIsNull(
            String tenantId, Loan.Status status, Instant dueAt);

}
