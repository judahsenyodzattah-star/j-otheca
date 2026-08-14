package com.libranet.ai.web;

import com.libranet.ai.service.AiQuotaService;
import com.libranet.ai.service.AssistantService;
import com.libranet.ai.service.AssistantService.AssistantResponse;
import com.libranet.ai.service.DemandInsightsService;
import com.libranet.ai.service.DemandInsightsService.DemandInsights;
import com.libranet.ai.service.EmbeddingService;
import com.libranet.ai.service.RecommendationService;
import com.libranet.ai.service.RecommendationService.RecommendationResult;
import com.libranet.ai.service.SemanticSearchService;
import com.libranet.ai.service.SemanticSearchService.SemanticSearchResult;
import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.membership.model.LibraryRole;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/**
 * AI surface (Phase 10, spec §16/§32). Every feature is plan-metered via AiQuotaService and
 * strictly tenant-scoped — semantic ranking happens only over the caller's own library.
 */
@RestController
@RequestMapping("/api/v1/tenant/ai")
public class TenantAiController {

    private final SemanticSearchService search;
    private final RecommendationService recommendations;
    private final AssistantService assistant;
    private final DemandInsightsService insights;
    private final AiQuotaService quota;
    private final EmbeddingService embeddings;
    private final com.libranet.ai.embed.EmbeddingProvider embedder;
    private final AuditService audit;

        private final PermissionService permissions;

    public TenantAiController(SemanticSearchService search, RecommendationService recommendations,
                              AssistantService assistant, DemandInsightsService insights,
                              AiQuotaService quota, EmbeddingService embeddings,
                              com.libranet.ai.embed.EmbeddingProvider embedder, AuditService audit,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.search = search;
        this.recommendations = recommendations;
        this.assistant = assistant;
        this.insights = insights;
        this.quota = quota;
        this.embeddings = embeddings;
        this.embedder = embedder;
        this.audit = audit;
    }

    /**
     * Semantic catalog search: "beginner Java books after 2023". Members (patrons see
     * published titles only; staff may add includeUnpublished=true).
     */
    @Operation(summary = "Semantic catalog search",
            description = "Natural-language search over THIS library's catalog only (tenant-isolation "
                    + "layer 9). Query understanding extracts year filters (\"after 2023\"); hits carry "
                    + "matchedTokens/why explanations. Metered against plan limits.aiCallsMonthly.")
    @GetMapping("/search")
    public SemanticSearchResult search(@RequestParam String q,
                                       @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) Boolean includeUnpublished) {
        return search.search(q, limit, includeUnpublished);
    }

    /** "What should I read next?" — per-membership recommendations with reasons. */
    @Operation(summary = "Personal recommendations",
            description = "\"What should I read next?\" — weighted centroid of the caller's own loans, "
                    + "ratings >=4 and want-it marks; consumed titles are never echoed. Cold start: "
                    + "library top-rated. Metered.")
    @GetMapping("/recommendations")
    public RecommendationResult recommendations(@RequestParam(required = false) Integer limit) {
        return recommendations.recommend(limit);
    }

    /**
     * The LibraNet assistant. Staff intents answer with tenant-wide operational metrics;
     * patrons (audit P1·E8 — spec §15/§16) get the same honesty scoped to their own
     * membership: my loans, my holds, library info, this library's real loan policy, and
     * catalog search with citations. Staff-metric wording from a patron returns a polite
     * STAFF_VIEW redirect — never the data, never a 403 wall.
     */
    @Operation(summary = "Library AI assistant (staff + patrons)",
            description = "Staff: DEMAND/OVERDUE/TOP_RATED/SEARCH intents. Patrons: MY_LOANS/MY_HOLDS/"
                    + "LIBRARY_INFO/POLICY/SEARCH scoped to their own membership; staff metrics are "
                    + "never exposed (STAFF_VIEW redirect instead). Audit stores intent + question "
                    + "length — never the question text. Plan-metered.")
    @PostMapping("/assistant/ask")
    public AssistantResponse ask(@jakarta.validation.Valid @RequestBody AskRequest req) {
        var ctx = com.libranet.common.tenant.TenantContext.require();
        if (ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name())) {
            return assistant.ask(req.question());
        }
        return assistant.askPatron(req.question());
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {}

    /**
     * Support-issue drafting (audit P2 — spec §20): the assistant maps the reporter's own
     * words to a category + subject and hands back a confirm card. It NEVER creates the
     * issue — the client calls POST /tenant/issues with source=AI only after the user
     * taps Confirm. Metered like any assistant call.
     */
    @Operation(summary = "Draft a support issue (confirm-to-create)",
            description = "Converts the member's own description into a structured issue draft "
                    + "(category suggestion + verbatim subject/description). Never creates anything: "
                    + "the draft returns confirmationRequired=true and the issue is only opened when "
                    + "the reporter confirms and the client posts it to /tenant/issues. Nothing is "
                    + "fabricated — the draft contains only the member's words. Plan-metered.")
    @PostMapping("/issues/draft")
    public AssistantService.IssueDraft issueDraft(@jakarta.validation.Valid @RequestBody IssueDraftRequest req) {
        return assistant.issueDraft(req.description());
    }

    public record IssueDraftRequest(@NotBlank @Size(max = 10000) String description) {}

    /** Staff demand insights: want-it marks + reservation queues + zero-hit searches. */
    @Operation(summary = "Acquisition demand insights",
            description = "Staff only: want-it marks + reservation queue depth per title, plus zero-hit "
                    + "member searches as uncatalogued demand. Metered.")
    @GetMapping("/demand-insights")
    public DemandInsights demandInsights() {
        return insights.insights();
    }

    /** Staff: current-month AI usage vs the plan limit. */
    @Operation(summary = "AI usage vs plan limit",
            description = "Staff only: current-month AI calls by feature against the plan's aiCallsMonthly "
                    + "ceiling. Overage on any AI feature answers 403 PLAN_LIMIT_EXCEEDED.")
    @GetMapping("/usage")
    public AiQuotaService.UsageView usage() {
        var ctx = permissions.require(LibraryPerm.REPORTS_VIEW);
        return quota.current(ctx.libraryId());
    }

    /** LIBRARY_ADMIN: rebuild this tenant's embeddings (e.g. after an import or model change). */
    @Operation(summary = "Rebuild tenant embeddings",
            description = "LIBRARY_ADMIN only. Full tenant re-embed (e.g. after a bulk import or model "
                    + "change); never touches another tenant's rows. Audited.")
    @PostMapping("/embeddings/rebuild")
    public Map<String, Object> rebuild() {
        String tenantId = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name()).libraryId();
        int count = embeddings.rebuildTenant(tenantId);
        audit.log(AuditAction.EMBEDDINGS_REBUILT, "Tenant", tenantId, Map.of("count", count));
        return Map.of("tenantId", tenantId, "embeddings", count, "model", embedder.modelName());
    }
}
