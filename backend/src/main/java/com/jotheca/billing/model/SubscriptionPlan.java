package com.jotheca.billing.model;

import com.jotheca.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A subscription tier. {@code limitsJson} is the ONLY source of plan limits (spec §16). */
@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "price_monthly_minor", nullable = false)
    private long priceMonthlyMinor;

    @Column(nullable = false, length = 3)
    private String currency = "GHS";

    @Column(name = "limits", columnDefinition = "TEXT", nullable = false)
    private String limitsJson;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long getPriceMonthlyMinor() { return priceMonthlyMinor; }
    public void setPriceMonthlyMinor(long priceMonthlyMinor) { this.priceMonthlyMinor = priceMonthlyMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getLimitsJson() { return limitsJson; }
    public void setLimitsJson(String limitsJson) { this.limitsJson = limitsJson; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
