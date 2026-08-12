package com.libranet.ai.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** AI call metering against the plan's aiCallsMonthly limit (Phase 10, spec §16). */
@Entity
@Table(name = "ai_usage_counters")
public class AiUsageCounter extends TenantScopedEntity {

    public enum Feature { SEMANTIC_SEARCH, RECOMMENDATIONS, ASSISTANT, DEMAND_INSIGHTS }

    @Column(nullable = false, length = 30)
    private String feature;

    @Column(name = "month_key", nullable = false, length = 7)
    private String monthKey; // 'YYYY-MM'

    @Column(nullable = false)
    private int calls;

    public String getFeature() { return feature; }
    public void setFeature(String feature) { this.feature = feature; }
    public String getMonthKey() { return monthKey; }
    public void setMonthKey(String monthKey) { this.monthKey = monthKey; }
    public int getCalls() { return calls; }
    public void setCalls(int calls) { this.calls = calls; }
}
