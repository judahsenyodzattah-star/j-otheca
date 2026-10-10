package com.jotheca.ai.repo;

import com.jotheca.ai.model.AiUsageCounter;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUsageCounterRepository extends JpaRepository<AiUsageCounter, String> {

    Optional<AiUsageCounter> findByTenantIdAndFeatureAndMonthKey(String tenantId, String feature,
                                                                 String monthKey);

    List<AiUsageCounter> findByTenantIdAndMonthKey(String tenantId, String monthKey);

    /** Total AI calls this calendar month across all features (the plan-metered number). */
    @Query("select coalesce(sum(c.calls), 0) from AiUsageCounter c"
            + " where c.tenantId = :tenantId and c.monthKey = :monthKey")
    long totalForMonth(@Param("tenantId") String tenantId, @Param("monthKey") String monthKey);
}
