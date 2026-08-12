package com.libranet.ai.service;

import com.libranet.ai.embed.EmbeddingProvider;
import com.libranet.ai.model.AiUsageCounter;
import com.libranet.ai.model.BookEmbedding;
import com.libranet.ai.service.QueryUnderstanding.ParsedQuery;
import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.membership.model.LibraryRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-filtered semantic search (Phase 10). The candidate set is loaded with
 * {@code WHERE tenant_id = :current} BEFORE any vector math — no code path can score a
 * foreign tenant's rows (isolation layer 9). On real Postgres with
 * app.ai.search.backend=pgvector, ranking moves into the DB's HNSW index with the same
 * tenant predicate; the Java cosine backend here is the portable default and the
 * reference implementation for tests.
 *
 * Scoring = 0.7 · embedding cosine + 0.3 · lexical token coverage, both explainable —
 * every hit reports WHY it matched. Structured filters ("after 2023") always win over
 * similarity: a year-mismatched book is excluded no matter how close the vector.
 */
@Service
public class SemanticSearchService {

    public record SemanticHit(String bookId, String title, String author, String slug,
                              Integer publishedYear, double score, List<String> matchedTokens,
                              List<String> why) {}

    public record SemanticSearchResult(String query, ParsedQuery filters, int candidateCount,
                                       List<SemanticHit> hits, AiQuotaService.UsageView usage) {}

    private static final double WEIGHT_VECTOR = 0.7;
    private static final double WEIGHT_LEXICAL = 0.3;
    private static final double MIN_SCORE = 0.05;

    private final EmbeddingService embeddingService;
    private final BookRepository books;
    private final EmbeddingProvider provider;
    private final AiQuotaService quota;
    private final AuditService audit;
    private final String searchBackend;
    private final com.libranet.ai.repo.BookEmbeddingRepository embeddingRepo;

    public SemanticSearchService(EmbeddingService embeddingService, BookRepository books,
                                 EmbeddingProvider provider, AiQuotaService quota, AuditService audit,
                                 com.libranet.ai.repo.BookEmbeddingRepository embeddingRepo,
                                 @Value("${app.ai.search.backend:java}") String searchBackend) {
        this.embeddingService = embeddingService;
        this.books = books;
        this.provider = provider;
        this.quota = quota;
        this.audit = audit;
        this.embeddingRepo = embeddingRepo;
        this.searchBackend = searchBackend;
    }

    @Transactional
    public SemanticSearchResult search(String rawQuery, Integer limit, Boolean includeUnpublished) {
        TenantContext.Current ctx = TenantContext.require();
        String tenantId = ctx.libraryId();
        if (rawQuery == null || rawQuery.isBlank()) {
            throw ApiException.badRequest("QUERY_REQUIRED", "q is required");
        }
        int max = limit == null ? 10 : Math.min(Math.max(limit, 1), 50);
        boolean staff = ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        boolean withUnpublished = staff && Boolean.TRUE.equals(includeUnpublished);

        // metered: one AI call per search, plan-capped
        AiQuotaService.UsageView usage = quota.require(tenantId, AiUsageCounter.Feature.SEMANTIC_SEARCH);

        ParsedQuery parsed = QueryUnderstanding.parse(rawQuery);
        if (parsed.text().isBlank()) {
            throw ApiException.badRequest("QUERY_REQUIRED",
                    "The query has no searchable text once filters are extracted");
        }

        List<BookEmbedding> candidates = embeddingService.freshEmbeddingsOf(tenantId); // self-heals
        float[] queryVector = provider.embed(parsed.text());

        // ANN fast-path (real Postgres + app.ai.search.backend=pgvector): the DB's HNSW index
        // returns the tenant's nearest ids — still WHERE tenant_id first; fall back to the
        // portable Java ranking when unavailable. Filters + explainability are applied either way.
        Set<String> annWinners = null;
        if ("pgvector".equalsIgnoreCase(searchBackend)) {
            try {
                annWinners = new HashSet<>(embeddingRepo.nearestBookIdsNative(tenantId,
                        EmbeddingProvider.toVectorLiteral(queryVector), max * 4));
            } catch (Exception e) {
                annWinners = null; // e.g. pgvector extension missing — java ranking remains correct
            }
        }
        Set<String> queryTokens = new HashSet<>(List.of(
                com.libranet.ai.embed.LocalHashEmbedder.normalize(parsed.text())));

        // books of THIS tenant only; embeddings without a live book are ignored defensively
        Map<String, Book> bookById = new java.util.HashMap<>();
        books.findAll((root, q, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.equal(root.get("published"), true))).forEach(b -> bookById.put(b.getId(), b));
        if (withUnpublished) {
            books.findAll((root, q, cb) -> cb.and(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.equal(root.get("published"), false))).forEach(b -> bookById.put(b.getId(), b));
        }

        List<SemanticHit> hits = new ArrayList<>();
        for (BookEmbedding row : candidates) {
            Book book = bookById.get(row.getBookId());
            if (book == null) continue;
            if (annWinners != null && !annWinners.contains(row.getBookId())) continue;
            if (!QueryUnderstanding.matchesYear(book.getPublishedDate(), parsed.publishedAfter(),
                    parsed.publishedBefore(), parsed.publishedIn())) {
                continue; // structured filters beat similarity
            }
            double vector = EmbeddingService.cosine(queryVector,
                    EmbeddingProvider.fromVectorLiteral(row.getEmbedding()));
            Set<String> docTokens = new HashSet<>(List.of(
                    com.libranet.ai.embed.LocalHashEmbedder.normalize(
                            embeddingService.textOf(book, null))));
            List<String> matched = queryTokens.stream().filter(docTokens::contains).sorted().toList();
            double lexical = queryTokens.isEmpty() ? 0 : (double) matched.size() / queryTokens.size();
            double score = WEIGHT_VECTOR * Math.max(0, vector) + WEIGHT_LEXICAL * lexical;
            if (score < MIN_SCORE || matched.isEmpty() && vector < 0.15) {
                continue; // spare users from noise that only the vector tail produced
            }
            List<String> why = new ArrayList<>();
            why.add("semantic similarity " + String.format(java.util.Locale.ROOT, "%.2f", Math.max(0, vector)));
            if (!matched.isEmpty()) {
                why.add("matched: " + String.join(", ", matched));
            }
            hits.add(new SemanticHit(book.getId(), book.getTitle(), book.getAuthor(), book.getSlug(),
                    QueryUnderstanding.yearOf(book.getPublishedDate()),
                    Math.round(score * 1000.0) / 1000.0, matched, why));
        }
        hits.sort(Comparator.comparingDouble(SemanticHit::score).reversed()
                .thenComparing(SemanticHit::title));
        List<SemanticHit> top = hits.stream().limit(max).toList();

        audit.log(AuditAction.AI_SEARCH_PERFORMED, "Tenant", tenantId, Map.of(
                "queryChars", rawQuery.length(), "hits", top.size(),
                "candidates", candidates.size()));
        return new SemanticSearchResult(rawQuery, parsed, bookById.size(), top, usage);
    }
}
