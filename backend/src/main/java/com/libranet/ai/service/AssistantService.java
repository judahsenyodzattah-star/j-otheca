package com.libranet.ai.service;

import com.libranet.ai.model.AiUsageCounter;
import com.libranet.ai.service.SemanticSearchService.SemanticSearchResult;
import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.circulation.model.Loan;
import com.libranet.circulation.repo.LoanRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.media.model.BookReview;
import com.libranet.media.repo.BookReviewRepository;
import com.libranet.membership.model.LibraryRole;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The guarded librarian assistant (Phase 10). Staff-only; every call is plan-metered.
 * Answers are grounded in THIS tenant's data: intents route to the same services the
 * dashboards use, and free-form questions fall back to tenant-filtered semantic search
 * with citations. The question text is NEVER written to logs or the audit trail — only
 * its length, intent and citation count (audit hygiene: member questions can name people).
 */
@Service
public class AssistantService {

    public record Citation(String bookId, String title, String slug) {}

    public record AssistantResponse(String intent, String answer, List<Citation> citations,
                                    AiQuotaService.UsageView usage) {}

    private final SemanticSearchService search;
    private final DemandInsightsService insights;
    private final com.libranet.catalog.repo.BookRepository books;
    private final LoanRepository loans;
    private final BookReviewRepository reviews;
    private final AiQuotaService quota;
    private final AuditService audit;

    public AssistantService(SemanticSearchService search, DemandInsightsService insights,
                            com.libranet.catalog.repo.BookRepository books,
                            LoanRepository loans, BookReviewRepository reviews,
                            AiQuotaService quota, AuditService audit) {
        this.search = search;
        this.insights = insights;
        this.books = books;
        this.loans = loans;
        this.reviews = reviews;
        this.quota = quota;
        this.audit = audit;
    }

    @Transactional
    public AssistantResponse ask(String question) {
        TenantContext.Current ctx = TenantGuard.requireRole(
                LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        String tenantId = ctx.libraryId();
        if (question == null || question.isBlank() || question.trim().length() < 3) {
            throw ApiException.badRequest("QUESTION_REQUIRED", "Ask a real question (3+ characters).");
        }
        if (question.length() > 500) {
            throw ApiException.badRequest("QUESTION_TOO_LONG", "Keep questions under 500 characters.");
        }
        AiQuotaService.UsageView usage = quota.require(tenantId, AiUsageCounter.Feature.ASSISTANT);
        String q = question.toLowerCase(Locale.ROOT);

        AssistantResponse response;
        if (mentions(q, "buy", "acquire", "purchase", "stock", "demand", "copies")) {
            response = demandAnswer(usage);
        } else if (mentions(q, "overdue", "late", "behind")) {
            response = overdueAnswer(tenantId, usage);
        } else if (mentions(q, "rated", "loved", "best", "review")) {
            response = ratedAnswer(tenantId, usage);
        } else {
            response = searchAnswer(question, usage);
        }

        // audit hygiene: intent + sizes only, never the question
        audit.log(AuditAction.AI_ASSISTANT_ASKED, "Tenant", tenantId, Map.of(
                "intent", response.intent(), "questionChars", question.trim().length(),
                "citations", response.citations().size()));
        return response;
    }

    // ---------- intents ----------

    private AssistantResponse demandAnswer(AiQuotaService.UsageView usage) {
        var i = insights.insights(); // bills DEMAND_INSIGHTS too — visible in usage
        StringBuilder sb = new StringBuilder("Demand signals right now: ");
        if (i.topDemanded().isEmpty() && i.uncataloguedDemand().isEmpty()) {
            sb.append("none recorded yet. As members search, reserve and flag titles, "
                    + "this view becomes your acquisition list.");
        } else {
            i.topDemanded().forEach(d -> sb.append(String.format(Locale.ROOT,
                    "\"%s\" (%d want-it marks, %d queued — %s). ",
                    d.title(), d.interests(), d.waitingReservations(), d.suggestion())));
            i.uncataloguedDemand().forEach(z -> sb.append(String.format(Locale.ROOT,
                    "Members searched \"%s\" %d× with no hit — %s. ",
                    z.query(), z.occurrences(), z.suggestion())));
        }
        List<Citation> citations = i.topDemanded().stream()
                .map(d -> new Citation(d.bookId(), d.title(), d.slug())).toList();
        return new AssistantResponse("DEMAND", sb.toString().trim(), citations,
                insightsUsage(usage, i.usage()));
    }

    private AssistantResponse overdueAnswer(String tenantId, AiQuotaService.UsageView usage) {
        long active = loans.countByTenantIdAndStatus(tenantId, Loan.Status.ACTIVE);
        long overdue = loans.countByTenantIdAndStatusAndDueAtBefore(tenantId, Loan.Status.ACTIVE,
                Instant.now());
        long pendingReviews = reviews.countByTenantIdAndStatus(tenantId, BookReview.ReviewStatus.PENDING);
        String answer = String.format(Locale.ROOT,
                "%d loan(s) are active, %d of them overdue. The hold-shelf and overdue lists are "
                        + "under Circulation → Loans. %d review(s) are waiting for moderation%s.",
                active, overdue, pendingReviews,
                pendingReviews > 0 ? " — worth a sweep" : "");
        return new AssistantResponse("OVERDUE", answer, List.of(), usage);
    }

    private AssistantResponse ratedAnswer(String tenantId, AiQuotaService.UsageView usage) {
        var top = books
                .findByTenantIdAndPublishedTrueAndRatingCountGreaterThanEqualOrderByRatingAvgDescRatingCountDesc(
                        tenantId, 1, org.springframework.data.domain.PageRequest.of(0, 5))
                .getContent();
        String answer = top.isEmpty()
                ? "No rated titles yet — ratings appear here once members start scoring books."
                : "Highest-rated in your library right now: "
                        + String.join("; ", top.stream()
                                .map(b -> String.format(Locale.ROOT, "\"%s\" (%.1f★ over %d ratings)",
                                        b.getTitle(), b.getRatingAvg(), b.getRatingCount()))
                                .toList()) + ".";
        List<Citation> citations = top.stream()
                .map(b -> new Citation(b.getId(), b.getTitle(), b.getSlug())).toList();
        return new AssistantResponse("TOP_RATED", answer, citations, usage);
    }

    private AssistantResponse searchAnswer(String question, AiQuotaService.UsageView usage) {
        SemanticSearchResult result = search.search(question, 3, false);
        if (result.hits().isEmpty()) {
            return new AssistantResponse("SEARCH",
                    "I couldn't find anything in this library matching that. Try the member "
                            + "search, or add the title to your acquisition list.",
                    List.of(), usage);
        }
        List<Citation> citations = result.hits().stream()
                .map(h -> new Citation(h.bookId(), h.title(), h.slug())).toList();
        String answer = "Best matches in your catalog: " + String.join("; ", result.hits().stream()
                .map(h -> String.format(Locale.ROOT, "\"%s\" by %s (%s)", h.title(),
                        h.author(), h.why().isEmpty() ? "semantic match" : String.join(" + ", h.why())))
                .toList()) + ".";
        return new AssistantResponse("SEARCH", answer, citations, usage);
    }

    private static boolean mentions(String q, String... words) {
        for (String w : words) {
            if (q.contains(w)) return true;
        }
        return false;
    }

    private static AiQuotaService.UsageView insightsUsage(AiQuotaService.UsageView outer,
                                                          AiQuotaService.UsageView inner) {
        return inner; // the nested insights call re-metered; report the freshest totals
    }
}
