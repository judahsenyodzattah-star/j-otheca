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

    public TenantAiController(SemanticSearchService search, RecommendationService recommendations,
                              AssistantService assistant, DemandInsightsService insights,
                              AiQuotaService quota, EmbeddingService embeddings,
                              com.libranet.ai.embed.EmbeddingProvider embedder, AuditService audit) {
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

    /** Guarded librarian assistant (staff only). Answers are grounded in tenant data. */
    @Operation(summary = "Guarded librarian assistant",
            description = "Staff only (patrons get 403). Intents DEMAND/OVERDUE/TOP_RATED/SEARCH; answers "
                    + "are grounded in tenant data with catalog citations. Audit stores the intent and the "
                    + "question length — never the question text. Metered.")
    @PostMapping("/assistant/ask")
    public AssistantResponse ask(@jakarta.validation.Valid @RequestBody AskRequest req) {
        return assistant.ask(req.question());
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {}

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
        var ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
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
