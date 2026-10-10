package com.jotheca.ai.repo;

import com.jotheca.ai.model.SearchEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SearchEventRepository extends JpaRepository<SearchEvent, String> {

    /**
     * Uncatalogued demand: zero-hit member searches grouped by normalized query, newest first
     * by frequency (top rows limited by the service via a Pageable-free sublist — event volume
     * per tenant is modest and this runs on the staff dashboard, not a hot path).
     */
    @Query("SELECT e.query, COUNT(e), MAX(e.createdAt) FROM SearchEvent e "
            + "WHERE e.tenantId = :tenantId AND e.resultCount = 0 "
            + "GROUP BY e.query ORDER BY COUNT(e) DESC")
    List<Object[]> topZeroHitQueries(@Param("tenantId") String tenantId);

    @Query("select count(e) from SearchEvent e where e.tenantId = :tenantId")
    long countForTenant(@Param("tenantId") String tenantId);
}
