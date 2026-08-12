package com.libranet.publishing.repo;

import com.libranet.publishing.model.PurchaseRequest;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, String> {

    List<PurchaseRequest> findByTenantIdAndMembershipIdOrderByCreatedAtDesc(String tenantId,
                                                                            String membershipId);

    List<PurchaseRequest> findByTenantIdAndStatusOrderByCreatedAtAsc(String tenantId,
                                                                     PurchaseRequest.Status status);

    boolean existsByTenantIdAndBookIdAndMembershipIdAndStatus(String tenantId, String bookId,
                                                              String membershipId,
                                                              PurchaseRequest.Status status);
}
