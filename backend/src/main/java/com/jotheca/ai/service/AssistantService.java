package com.jotheca.ai.service;

import com.jotheca.ai.model.AiUsageCounter;
import com.jotheca.ai.service.SemanticSearchService.SemanticSearchResult;
import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.circulation.model.Loan;
import com.jotheca.circulation.model.LoanPolicy;
import com.jotheca.circulation.model.Reservation;
import com.jotheca.circulation.repo.LoanPolicyRepository;
import com.jotheca.circulation.repo.LoanRepository;
import com.jotheca.circulation.repo.ReservationRepository;
import com.jotheca.common.error.ApiException;
import com.jotheca.common.tenant.TenantContext;
import com.jotheca.common.tenant.TenantGuard;
import com.jotheca.issues.repo.IssueRepository;
import com.jotheca.media.model.BookReview;
import com.jotheca.media.repo.BookReviewRepository;
import com.jotheca.membership.model.LibraryRole;
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

    /**
     * Assistant-drafted support issue (audit P2 — spec §20): the assistant CLASSIFIES and
     * TRIMS the reporter's own words — it never invents categories beyond keyword mapping,
     * never fills in facts, and never creates the issue. {@code confirmationRequired} is a
     * permanent {@code true}: the client must show the draft and the user must confirm
     * before {@code POST /tenant/issues} is called with {@code source=AI}.
     */
    public record IssueDraft(String category, String subject, String description,
                             String confirmation, boolean confirmationRequired) {}

    private final SemanticSearchService search;
    private final DemandInsightsService insights;
    private final com.jotheca.catalog.repo.BookRepository books;
    private final LoanRepository loans;
    private final ReservationRepository reservations;
    private final LoanPolicyRepository policies;
    private final com.jotheca.tenancy.repo.LibraryRepository libraries;
    private final BookReviewRepository reviews;
    private final IssueRepository issueRepo;
    private final AiQuotaService quota;
    private final AuditService audit;

    public AssistantService(SemanticSearchService search, DemandInsightsService insights,
                            com.jotheca.catalog.repo.BookRepository books,
                            LoanRepository loans, ReservationRepository reservations,
                            LoanPolicyRepository policies,
                            com.jotheca.tenancy.repo.LibraryRepository libraries,
                            BookReviewRepository reviews, IssueRepository issueRepo,
                            AiQuotaService quota, AuditService audit) {
        this.search = search;
        this.insights = insights;
        this.books = books;
        this.loans = loans;
        this.reservations = reservations;
        this.policies = policies;
        this.libraries = libraries;
        this.reviews = reviews;
        this.issueRepo = issueRepo;
        this.quota = quota;
        this.audit = audit;
    }

    /**
     * Draft a support issue from the reporter's own words (audit P2 — spec §20). Open to any
     * active member; plan-metered like every assistant call. The draft is verbatim text plus a
     * keyword-mapped category suggestion — nothing is fabricated, nothing is created here.
     */
    @Transactional
    public IssueDraft issueDraft(String description) {
        TenantContext.Current ctx = TenantContext.require();
        String tenantId = ctx.libraryId();
        if (description == null || description.isBlank() || description.trim().length() < 10) {
            throw ApiException.badRequest("ISSUE_DESCRIPTION_REQUIRED",
                    "Describe what happened in at least 10 characters — in your own words.");
        }
        if (description.length() > 10000) {
            throw ApiException.badRequest("ISSUE_DESCRIPTION_TOO_LONG",
                    "Keep the description under 10,000 characters.");
        }
        quota.require(tenantId, AiUsageCounter.Feature.ASSISTANT);

        String text = description.trim();
        String category = classifyIssue(text);
        String subject = draftSubject(text);

        audit.log(AuditAction.AI_ISSUE_DRAFTED, "Tenant", tenantId, Map.of(
                "category", category, "chars", text.length()));
        return new IssueDraft(category, subject, text,
                "Here's the report I drafted from your words. Check the category and details — "
                        + "the library team gets it only when you tap Confirm.",
                true);
    }

    /** Spec §22 keyword mapping — deterministic; the reporter can change it before confirming. */
    static String classifyIssue(String text) {
        String q = text.toLowerCase(Locale.ROOT);
        if (mentions(q, "log in", "login", "sign in", "password", "locked out", "verify my email",
                "verification", "account")) return "ACCOUNT_ACCESS";
        if (mentions(q, "pay", "payment", "charged", "charge", "paystack", "refund", "billing",
                "invoice", "momo", "mobile money", "card was debited", "debited")) return "PAYMENTS";
        if (mentions(q, "membership", "member card", "id number", "application", "approve",
                "join", "id card")) return "MEMBERSHIP";
        if (mentions(q, "loan", "borrow", "return", "due date", "renew my", "overdue", "fine",
                "checked out", "checkout", "hold", "reservation", "pickup")) return "LOANS";
        if (mentions(q, "crash", "error", "bug", "not working", "broken", "won't load", "wont load",
                "freeze", "frozen", "blank", "sync", "offline")) return "TECHNICAL";
        return "OTHER";
    }

    /** Verbatim first line (or first sentence), trimmed to subject length — never paraphrased. */
    static String draftSubject(String text) {
        String first = text.split("\\R", 2)[0].trim();
        int sentenceEnd = first.indexOf(". ");
        if (sentenceEnd > 0 && sentenceEnd <= 120) first = first.substring(0, sentenceEnd + 1);
        if (first.length() > 120) {
            int cut = first.lastIndexOf(' ', 117);
            first = first.substring(0, cut > 40 ? cut : 120).trim() + "…";
        }
        return first;
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
        if (mentions(q, "issue", "support", "ticket", "complaint", "helpdesk", "help desk")) {
            response = supportInboxAnswer(tenantId, usage);
        } else if (mentions(q, "buy", "acquire", "purchase", "stock", "demand", "copies")) {
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

    /**
     * Patron-facing assistant (audit P1·E8 — spec §15/§16): any active member of the
     * tenant can ask; every answer is scoped to the caller's own membership and the
     * tenant's published data. Staff-side metrics are NEVER exposed — staff-intent
     * wording from a patron gets a polite redirect (STAFF_VIEW), not a 403 wall and
     * never the data. Same plan metering, same audit hygiene (intent + length only).
     */
    @Transactional
    public AssistantResponse askPatron(String question) {
        TenantContext.Current ctx = TenantContext.require(); // active membership in this tenant
        String tenantId = ctx.libraryId();
        validateQuestion(question);
        AiQuotaService.UsageView usage = quota.require(tenantId, AiUsageCounter.Feature.ASSISTANT);
        String q = question.toLowerCase(Locale.ROOT);

        AssistantResponse response;
        if (mentions(q, "report an issue", "report a problem", "something is broken",
                "not working", "i was charged", "charged me", "can't log in", "cant log in",
                "cannot log in", "locked out", "complaint", "speak to a human",
                "talk to a human", "get help with", "need help")) {
            response = new AssistantResponse("REPORT_ISSUE",
                    "I can carry that to the library team as a support issue. Tap the draft "
                            + "button below (or Support → Report an issue) and I'll shape what you "
                            + "wrote into a structured report with a tracking number — you review "
                            + "it first, and nothing reaches the library until you confirm. I only "
                            + "pass on what you tell me; I never invent details.",
                    List.of(), usage);
        } else if (mentions(q, "should we buy", "acquire", "demand", "stock levels")) {
            response = new AssistantResponse("STAFF_VIEW",
                    "That's a librarian-side view I can't share from your member seat — but your "
                            + "signals count: a want-it mark or an empty-handed search both land on "
                            + "the acquisition radar. If you're trying to own a copy, use Purchase "
                            + "on the book's page.",
                    List.of(), usage);
        } else if (mentions(q, "my loan", "my book", "my borrow", "due", "checked out",
                "return my", "overdue", "late")) {
            response = myLoansAnswer(ctx, usage);
        } else if (mentions(q, "hold", "reserv", "pickup", "ready for me")) {
            response = myHoldsAnswer(ctx, usage);
        } else if (mentions(q, "hour", "open", "close", "where are you", "address",
                "contact", "location", "find the library")) {
            response = libraryInfoAnswer(ctx, usage);
        } else if (mentions(q, "renew", "how long", "keep a book", "keep it", "fine",
                "rule", "how many books", "limit", "policy")) {
            response = policyAnswer(tenantId, usage);
        } else {
            response = patronSearchAnswer(question, usage);
        }

        audit.log(AuditAction.AI_ASSISTANT_ASKED, "Tenant", tenantId, Map.of(
                "intent", response.intent(), "questionChars", question.trim().length(),
                "citations", response.citations().size(), "audience", "PATRON"));
        return response;
    }

    private static void validateQuestion(String question) {
        if (question == null || question.isBlank() || question.trim().length() < 3) {
            throw ApiException.badRequest("QUESTION_REQUIRED", "Ask a real question (3+ characters).");
        }
        if (question.length() > 500) {
            throw ApiException.badRequest("QUESTION_TOO_LONG", "Keep questions under 500 characters.");
        }
    }

    // ---------- patron intents ----------

    private AssistantResponse myLoansAnswer(TenantContext.Current ctx, AiQuotaService.UsageView usage) {
        var active = loans.findByTenantIdAndMembershipIdAndStatus(
                ctx.libraryId(), ctx.membershipId(), Loan.Status.ACTIVE);
        if (active.isEmpty()) {
            return new AssistantResponse("MY_LOANS",
                    "You have nothing on loan right now. Anything you borrow shows up here "
                            + "with its due date.", List.of(), usage);
        }
        var now = Instant.now();
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "You have %d book(s) on loan: ", active.size()));
        List<Citation> citations = new java.util.ArrayList<>();
        active.stream().limit(5).forEach(l -> {
            String title = books.findById(l.getBookId()).map(b -> b.getTitle()).orElse("A book");
            boolean overdue = l.getDueAt() != null && l.getDueAt().isBefore(now);
            sb.append(String.format(Locale.ROOT, "\"%s\" — due %s%s. ", title,
                    l.getDueAt() == null ? "—" : l.getDueAt().toString().substring(0, 10),
                    overdue ? " (overdue — return it to stop fines)" : ""));
            books.findById(l.getBookId()).ifPresent(b ->
                    citations.add(new Citation(b.getId(), b.getTitle(), b.getSlug())));
        });
        if (active.size() > 5) sb.append(String.format(Locale.ROOT, "…and %d more. ", active.size() - 5));
        sb.append("Renew from My books if you need more time.");
        return new AssistantResponse("MY_LOANS", sb.toString().trim(), citations, usage);
    }

    private AssistantResponse myHoldsAnswer(TenantContext.Current ctx, AiQuotaService.UsageView usage) {
        var mine = reservations
                .findByTenantIdAndMembershipIdOrderByCreatedAtDesc(ctx.libraryId(), ctx.membershipId())
                .stream().filter(Reservation::isInFlight).toList();
        if (mine.isEmpty()) {
            return new AssistantResponse("MY_HOLDS",
                    "No holds right now. Reserve an unavailable book and I'll keep track of "
                            + "your place in the queue.", List.of(), usage);
        }
        long ready = mine.stream().filter(r -> r.getStatus() == Reservation.Status.READY_FOR_PICKUP).count();
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "You have %d open hold(s)%s. ", mine.size(),
                ready > 0 ? " — " + ready + " ready for pickup" : ""));
        List<Citation> citations = new java.util.ArrayList<>();
        mine.stream().limit(5).forEach(r -> {
            var book = books.findById(r.getBookId());
            String title = book.map(b -> b.getTitle()).orElse("A book");
            if (r.getStatus() == Reservation.Status.READY_FOR_PICKUP) {
                sb.append(String.format(Locale.ROOT, "\"%s\" is waiting for you at the desk — "
                                + "collect by %s. ", title,
                        r.getPickupDeadlineAt() == null ? "the deadline"
                                : r.getPickupDeadlineAt().toString().substring(0, 10)));
            } else {
                sb.append(String.format(Locale.ROOT, "\"%s\" (%s). ", title,
                        r.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
            }
            book.ifPresent(b -> citations.add(new Citation(b.getId(), b.getTitle(), b.getSlug())));
        });
        return new AssistantResponse("MY_HOLDS", sb.toString().trim(), citations, usage);
    }

    private AssistantResponse libraryInfoAnswer(TenantContext.Current ctx, AiQuotaService.UsageView usage) {
        var lib = libraries.findByUid(ctx.libraryUid()).orElse(null);
        if (lib == null) {
            return new AssistantResponse("LIBRARY_INFO",
                    "I can't read this library's card right now — try the library's page.",
                    List.of(), usage);
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%s (%s library",
                lib.getName(), lib.getType().name().toLowerCase(Locale.ROOT)));
        if (lib.getCity() != null) sb.append(", ").append(lib.getCity());
        sb.append("). ");
        if (lib.getAddress() != null) sb.append("Address: ").append(lib.getAddress()).append(". ");
        if (lib.getOpeningHours() != null) {
            sb.append("Opening hours: ").append(lib.getOpeningHours()).append(". ");
        } else {
            sb.append("Opening hours aren't published here yet — check the notice board or ask at the desk. ");
        }
        if (lib.getServices() != null) sb.append("Services: ").append(lib.getServices()).append(".");
        return new AssistantResponse("LIBRARY_INFO", sb.toString().trim(), List.of(), usage);
    }

    private AssistantResponse policyAnswer(String tenantId, AiQuotaService.UsageView usage) {
        // Mirror CirculationService.getPolicy(): an uncustomized policy still ENFORCES the
        // entity defaults — so patrons must hear those numbers, never "not published".
        var p = policies.findByTenantId(tenantId).orElseGet(LoanPolicy::new);
        String answer = String.format(Locale.ROOT,
                "You can keep up to %d books at once, each for %d days, and renew up to %d time(s) "
                        + "for %d extra days per renewal. Overdue fines run %s %.2f per day. Holds are "
                        + "held for you for %d hours once ready. You can have %d active holds.",
                p.getMaxActiveLoans(), p.getLoanDays(), p.getMaxRenewals(), p.getRenewDays(),
                p.getCurrency(), p.getFinePerDayMinor() / 100.0, p.getPickupDeadlineHours(),
                p.getMaxReservations());
        return new AssistantResponse("POLICY", answer, List.of(), usage);
    }

    private AssistantResponse patronSearchAnswer(String question, AiQuotaService.UsageView usage) {
        SemanticSearchResult result = search.search(question, 3, false);
        if (result.hits().isEmpty()) {
            return new AssistantResponse("SEARCH",
                    "Nothing in this library matches that, as far as the catalog knows. "
                            + "Telling a librarian (or tapping want-it on a similar title) puts "
                            + "it on their acquisition radar.",
                    List.of(), usage);
        }
        List<Citation> citations = result.hits().stream()
                .map(h -> new Citation(h.bookId(), h.title(), h.slug())).toList();
        StringBuilder sb = new StringBuilder("From your library: ");
        result.hits().forEach(h -> sb.append(String.format(Locale.ROOT, "\"%s\" by %s. ", h.title(), h.author())));
        return new AssistantResponse("SEARCH", sb.toString().trim(), citations, usage);
    }

    // ---------- staff intents ----------

    private AssistantResponse supportInboxAnswer(String tenantId, AiQuotaService.UsageView usage) {
        long open = issueRepo.countByTenantIdAndStatus(tenantId, "OPEN");
        long needsInfo = issueRepo.countByTenantIdAndStatus(tenantId, "NEEDS_INFO");
        long inProgress = issueRepo.countByTenantIdAndStatus(tenantId, "IN_PROGRESS");
        long escalated = issueRepo.countByTenantIdAndStatus(tenantId, "ESCALATED");
        String answer = String.format(Locale.ROOT,
                "Support inbox: %d open, %d in progress, %d waiting on the reporter for info, "
                        + "%d escalated. Work them from the Desk → Support inbox — assigned "
                        + "and resolved items notify the member automatically.",
                open, inProgress, needsInfo, escalated);
        return new AssistantResponse("SUPPORT_INBOX", answer, List.of(), usage);
    }

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
