package com.jotheca.billing.repo;

import com.jotheca.billing.model.Payment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, String> {
    Optional<Payment> findByProviderAndProviderReference(Payment.Provider provider, String providerReference);
    List<Payment> findByPayerUserIdOrderByCreatedAtDesc(String payerUserId);
    List<Payment> findByOnboardingRequestId(String onboardingRequestId);
    /** Tenant-scoped payment history (Phase 9 billing self-service). */
    List<Payment> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** Platform analytics: everything ever captured (PAID rows only). */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(p.amountMinor), 0) from Payment p where p.status = com.jotheca.billing.model.Payment.Status.PAID")
    long totalPaidMinor();

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(p.amountMinor), 0) from Payment p where p.status = com.jotheca.billing.model.Payment.Status.PAID and p.purpose = :purpose")
    long totalPaidMinorByPurpose(@org.springframework.data.repository.query.Param("purpose") Payment.Purpose purpose);

}
