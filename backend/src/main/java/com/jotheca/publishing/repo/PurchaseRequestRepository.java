package com.jotheca.publishing.repo;

import com.jotheca.publishing.model.PurchaseRequest;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, String> {

    List<PurchaseRequest> findByTenantIdAndMembershipIdOrderByCreatedAtDesc(String tenantId,
                                                                            String membershipId);

    List<PurchaseRequest> findByTenantIdAndStatusOrderByCreatedAtAsc(String tenantId,
                                                                     PurchaseRequest.Status status);

    /** E17: studio aggregates across libraries — profiles are user-global, never tenant-bound. */
    List<PurchaseRequest> findByBookIdIn(Collection<String> bookIds);

    boolean existsByTenantIdAndBookIdAndMembershipIdAndStatus(String tenantId, String bookId,
                                                              String membershipId,
                                                              PurchaseRequest.Status status);
}
