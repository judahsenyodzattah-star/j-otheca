package com.jotheca.ai.service;

import com.jotheca.ai.model.SearchEvent;
import com.jotheca.ai.repo.SearchEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records member catalog searches (Phase 10 demand signal). Normalization caps at 300 chars,
 * lowercases, collapses whitespace. Kept separate from CatalogService so telemetry can never
 * break the search path (failures are logged, swallowed) and needs no catalog imports.
 */
@Service
public class SearchEventService {

    private static final Logger log = LoggerFactory.getLogger(SearchEventService.class);

    private final SearchEventRepository events;

    public SearchEventService(SearchEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW) // < readOnly callers would swallow the insert
    public void record(String tenantId, String rawQuery, int resultCount, String userId) {
        if (rawQuery == null || rawQuery.isBlank()) return;
        try {
            String normalized = rawQuery.trim().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("\\s+", " ");
            if (normalized.length() > 300) normalized = normalized.substring(0, 300);
            SearchEvent e = new SearchEvent();
            e.setTenantId(tenantId);
            e.setQuery(normalized);
            e.setResultCount(resultCount);
            e.setUserId(userId);
            events.save(e);
        } catch (Exception ex) {
            log.warn("search telemetry dropped: {}", ex.toString());
        }
    }
}
