package com.jotheca.circulation.repo;

import com.jotheca.circulation.model.AppNotification;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<AppNotification, String> {

    Page<AppNotification> findByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId,
                                                                      Pageable pageable);

    List<AppNotification> findByTenantIdAndUserIdAndReadAtIsNull(String tenantId, String userId);

    long countByTenantIdAndUserIdAndReadAtIsNull(String tenantId, String userId);
}
