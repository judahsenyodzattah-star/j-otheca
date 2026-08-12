package com.libranet.billing.repo;

import com.libranet.billing.model.LibrarySubscription;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibrarySubscriptionRepository extends JpaRepository<LibrarySubscription, String> {
    Optional<LibrarySubscription> findByTenantId(String tenantId);
    /** Platform analytics: MRR composition. */
    java.util.List<LibrarySubscription> findByStatus(LibrarySubscription.Status status);

}
