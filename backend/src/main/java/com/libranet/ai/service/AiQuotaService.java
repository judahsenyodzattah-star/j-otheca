package com.libranet.ai.service;

import com.libranet.ai.model.AiUsageCounter;
import com.libranet.ai.repo.AiUsageCounterRepository;
import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.billing.service.EntitlementService;
import com.libranet.common.error.ApiException;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI metering (Phase 10): every AI feature call bills one unit against the plan's
 * aiCallsMonthly. The limit comes from subscription_plans.limits via EntitlementService —
 * never from code. An exhausted plan answers 403 PLAN_LIMIT_EXCEEDED, audited.
 */
@Service
public class AiQuotaService {

    public record UsageView(long used, long limit, String month, java.util.Map<String, Long> byFeature) {}

    private final AiUsageCounterRepository counters;
    private final EntitlementService entitlements;
    private final AuditService audit;

    public AiQuotaService(AiUsageCounterRepository counters, EntitlementService entitlements,
                          AuditService audit) {
        this.counters = counters;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /** Bill one call for {@code feature} — or refuse. Called at the TOP of each AI endpoint. */
    @Transactional(propagation = Propagation.REQUIRED)
    public UsageView require(String tenantId, AiUsageCounter.Feature feature) {
        String month = YearMonth.now().toString();
        long limit = entitlements.limitsFor(tenantId).aiCallsMonthly();
        long used = counters.totalForMonth(tenantId, month);
        if (used >= limit) {
            audit.log(AuditAction.AI_QUOTA_EXCEEDED, "AiUsageCounter", tenantId + ":" + month,
                    Map.of("feature", feature.name(), "used", used, "limit", limit));
            throw ApiException.forbidden("PLAN_LIMIT_EXCEEDED",
                    "This library's plan allows " + limit + " AI calls per month (all used in " + month
                            + "). Upgrade the library's subscription for more.");
        }
        AiUsageCounter row = counters
                .findByTenantIdAndFeatureAndMonthKey(tenantId, feature.name(), month)
                .orElseGet(() -> {
                    AiUsageCounter c = new AiUsageCounter();
                    c.setTenantId(tenantId);
                    c.setFeature(feature.name());
                    c.setMonthKey(month);
                    c.setCalls(0);
                    return c;
                });
        row.setCalls(row.getCalls() + 1);
        counters.save(row);
        return current(tenantId);
    }

    @Transactional(readOnly = true)
    public UsageView current(String tenantId) {
        String month = YearMonth.now().toString();
        List<AiUsageCounter> rows = counters.findByTenantIdAndMonthKey(tenantId, month);
        java.util.Map<String, Long> byFeature = rows.stream().collect(java.util.stream.Collectors
                .toMap(AiUsageCounter::getFeature, c -> (long) c.getCalls(), Long::sum,
                        java.util.LinkedHashMap::new));
        long used = rows.stream().mapToLong(AiUsageCounter::getCalls).sum();
        return new UsageView(used, entitlements.limitsFor(tenantId).aiCallsMonthly(), month, byFeature);
    }
}
