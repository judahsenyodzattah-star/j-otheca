package com.jotheca.ai.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Member search telemetry (Phase 10 demand insights): normalized query + hit count.
 * Zero-result rows are the "uncatalogued demand" signal for acquisition.
 */
@Entity
@Table(name = "search_events")
public class SearchEvent extends TenantScopedEntity {

    @Column(nullable = false, length = 300)
    private String query;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "user_id", length = 36)
    private String userId;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public int getResultCount() { return resultCount; }
    public void setResultCount(int resultCount) { this.resultCount = resultCount; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
