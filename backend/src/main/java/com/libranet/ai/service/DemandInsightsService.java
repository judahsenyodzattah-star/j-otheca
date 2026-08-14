package com.libranet.ai.service;

import com.libranet.ai.model.AiUsageCounter;
import com.libranet.ai.repo.SearchEventRepository;
import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.circulation.model.Reservation;
import com.libranet.circulation.repo.ReservationRepository;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.membership.model.LibraryRole;
import com.libranet.publishing.repo.BookInterestRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/**
 * Demand insights (Phase 10): acquisition intelligence from three grounded signals —
 * "I want this" marks, pending reservation pressure, and zero-hit member searches —
 * all loaded with tenant predicates. Answers are numbers + titles, never invented text.
 */
@Service
public class DemandInsightsService {

    public record DemandedBook(String bookId, String title, String slug, long interests,
                               long waitingReservations, String suggestion) {}

    public record ZeroHitQuery(String query, long occurrences, String suggestion) {}

    public record DemandInsights(List<DemandedBook> topDemanded, List<ZeroHitQuery> uncataloguedDemand,
                                 List<String> summary, AiQuotaService.UsageView usage) {}

    private final BookInterestRepository interests;
    private final ReservationRepository reservations;
    private final SearchEventRepository searchEvents;
    private final BookRepository books;
    private final AiQuotaService quota;
    private final AuditService audit;

        private final PermissionService permissions;

    public DemandInsightsService(BookInterestRepository interests, ReservationRepository reservations,
                                 SearchEventRepository searchEvents, BookRepository books,
                                 AiQuotaService quota, AuditService audit,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.interests = interests;
        this.reservations = reservations;
        this.searchEvents = searchEvents;
        this.books = books;
        this.quota = quota;
        this.audit = audit;
    }

    @Transactional
    public DemandInsights insights() {
        TenantContext.Current ctx = permissions.require(LibraryPerm.REPORTS_VIEW);
        String tenantId = ctx.libraryId();
        AiQuotaService.UsageView usage = quota.require(tenantId, AiUsageCounter.Feature.DEMAND_INSIGHTS);

        // signal 1+2: per-book interests and queue pressure
        Map<String, Long> interestCounts = new HashMap<>();
        interests.topDemanded(tenantId, PageRequest.of(0, 5))
                .forEach(row -> interestCounts.put((String) row[0], (long) row[1]));
        Map<String, Long> waiting = new HashMap<>();
        reservations.findByTenantIdAndStatusOrderByCreatedAtAsc(tenantId, Reservation.Status.RESERVED)
                .forEach(r -> waiting.merge(r.getBookId(), 1L, Long::sum));
        reservations.findByTenantIdAndStatusOrderByCreatedAtAsc(tenantId, Reservation.Status.WAITING_FOR_PICKUP)
                .forEach(r -> waiting.merge(r.getBookId(), 1L, Long::sum));

        List<String> ids = new ArrayList<>(interestCounts.keySet());
        waiting.keySet().stream().filter(id -> !interestCounts.containsKey(id)).forEach(ids::add);
        Map<String, Book> byId = new HashMap<>();
        if (!ids.isEmpty()) books.findAllById(ids).forEach(b -> byId.put(b.getId(), b));

        List<DemandedBook> demanded = ids.stream()
                .map(id -> {
                    Book b = byId.get(id);
                    long want = interestCounts.getOrDefault(id, 0L);
                    long wait = waiting.getOrDefault(id, 0L);
                    String suggestion = wait > 0
                            ? "add copies — " + wait + " member(s) are queued"
                            : want >= 3 ? "consider extra copies — strong interest"
                                        : "monitor interest";
                    return new DemandedBook(id, b == null ? "(removed)" : b.getTitle(),
                            b == null ? null : b.getSlug(), want, wait, suggestion);
                })
                .sorted(java.util.Comparator.comparingLong((DemandedBook d) -> d.interests()
                        + d.waitingReservations()).reversed())
                .limit(5)
                .toList();

        // signal 3: zero-hit searches — what members ask for that we do not carry
        List<ZeroHitQuery> zeroHit = searchEvents.topZeroHitQueries(tenantId).stream()
                .limit(5)
                .map(row -> new ZeroHitQuery((String) row[0], (long) row[1],
                        "consider acquiring titles matching \"" + row[0] + "\""))
                .toList();

        List<String> summary = new ArrayList<>();
        summary.add(ids.isEmpty()
                ? "No demand signals recorded this period."
                : ids.size() + " title(s) show demand signals; " + waiting.values().stream()
                        .mapToLong(Long::longValue).sum() + " reservation(s) are waiting.");
        if (!zeroHit.isEmpty()) {
            summary.add(zeroHit.size() + " recurring member search(es) returned nothing — "
                    + "candidate acquisitions.");
        }

        audit.log(AuditAction.AI_DEMAND_INSIGHTS, "Tenant", tenantId, Map.of(
                "demanded", demanded.size(), "zeroHitQueries", zeroHit.size()));
        return new DemandInsights(demanded, zeroHit, summary, usage);
    }
}
