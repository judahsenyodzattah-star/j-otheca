package com.libranet.billing.repo;

import com.libranet.billing.model.SubscriptionPlan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, String> {
    Optional<SubscriptionPlan> findByCode(String code);
    List<SubscriptionPlan> findByActiveTrueOrderBySortOrder();
}
