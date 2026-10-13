package com.jotheca.ai.service;

import com.jotheca.ai.embed.EmbeddingProvider;
import com.jotheca.ai.model.AiUsageCounter;
import com.jotheca.ai.model.BookEmbedding;
import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.catalog.model.Book;
import com.jotheca.catalog.repo.BookRepository;
import com.jotheca.circulation.model.Loan;
import com.jotheca.circulation.repo.LoanRepository;
import com.jotheca.common.tenant.TenantContext;
import com.jotheca.media.repo.BookRatingRepository;
import com.jotheca.publishing.repo.BookInterestRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member recommendations (Phase 10): the member's taste vector = the mean of embeddings of
 * books they borrowed (×1), rated ≥4★ (×2) or flagged "I want this" (×1.5) — all within THIS
 * library. Candidates are the tenant's published books they haven't touched yet; every
 * recommendation carries a "because …" reason. Cold start falls back to the tenant's
 * top-rated/top-demanded titles. Never recommends across libraries: all inputs and all
 * candidates are loaded with tenant predicates only.
 */
@Service
public class RecommendationService {

    public record RecommendationDto(String bookId, String title, String author, String slug,
                                    double ratingAvg, int ratingCount, double score,
                                    List<String> because) {}

    public record RecommendationResult(String basis, List<RecommendationDto> recommendations,
                                       AiQuotaService.UsageView usage) {}

    private final EmbeddingService embeddingService;
    private final EmbeddingProvider provider;
    private final BookRepository books;
    private final LoanRepository loans;
    private final BookRatingRepository ratings;
    private final BookInterestRepository interests;
    private final AiQuotaService quota;
    private final AuditService audit;

    public RecommendationService(EmbeddingService embeddingService, EmbeddingProvider provider,
                                 BookRepository books, LoanRepository loans,
                                 BookRatingRepository ratings, BookInterestRepository interests,
                                 AiQuotaService quota, AuditService audit) {
        this.embeddingService = embeddingService;
        this.provider = provider;
        this.books = books;
        this.loans = loans;
        this.ratings = ratings;
        this.interests = interests;
        this.quota = quota;
        this.audit = audit;
    }

    @Transactional
    public RecommendationResult recommend(Integer limit) {
        TenantContext.Current ctx = TenantContext.require();
        String tenantId = ctx.libraryId();
        int max = limit == null ? 8 : Math.min(Math.max(limit, 1), 30);
        AiQuotaService.UsageView usage = quota.require(tenantId, AiUsageCounter.Feature.RECOMMENDATIONS);

        // ---- taste profile (this membership, this library only) ----
        Map<String, Double> weights = new HashMap<>();  // bookId -> taste weight
        Map<String, List<String>> becauseMap = new HashMap<>();
        loans.findByTenantIdAndMembershipIdAndStatus(tenantId, ctx.membershipId(), Loan.Status.RETURNED)
                .forEach(l -> bump(weights, becauseMap, l.getBookId(), 1.0, "you borrowed this"));
        loans.findByTenantIdAndMembershipIdAndStatus(tenantId, ctx.membershipId(), Loan.Status.ACTIVE)
                .forEach(l -> bump(weights, becauseMap, l.getBookId(), 1.0, "you are reading this"));
        ratings.findByTenantIdAndMembershipId(tenantId, ctx.membershipId()).stream()
                .filter(r -> r.getStars() >= 4)
                .forEach(r -> bump(weights, becauseMap, r.getBookId(), 2.0,
                        "you rated this " + r.getStars() + "★"));
        interests.findByTenantIdAndMembershipId(tenantId, ctx.membershipId())
                .forEach(i -> bump(weights, becauseMap, i.getBookId(), 1.5, "you asked for this"));

        List<BookEmbedding> embeddingRows = embeddingService.freshEmbeddingsOf(tenantId);
        Map<String, BookEmbedding> byBook = new HashMap<>();
        embeddingRows.forEach(e -> byBook.put(e.getBookId(), e));

        List<Book> catalog = books.findAll((root, q, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId), cb.equal(root.get("published"), true)));

        List<RecommendationDto> out;
        String basis;
        Set<String> consumed = new HashSet<>(weights.keySet());
        if (weights.isEmpty() || byBook.isEmpty()) {
            basis = "cold-start (top-rated & most wanted in this library)";
            out = coldStart(catalog, max);
        } else {
            basis = "your loans, ratings and interests in this library";
            // weighted centroid
            int dims = provider.dims();
            double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            float[] profile = new float[dims];
            weights.forEach((bookId, w) -> {
                BookEmbedding e = byBook.get(bookId);
                if (e == null) return;
                float[] v = EmbeddingProvider.fromVectorLiteral(e.getEmbedding());
                for (int d = 0; d < Math.min(dims, v.length); d++) profile[d] += (float) (v[d] * w / total);
            });
            // re-normalize
            double norm = 0;
            for (float f : profile) norm += f * f;
            norm = Math.sqrt(norm);
            if (norm > 0) for (int i = 0; i < dims; i++) profile[i] = (float) (profile[i] / norm);

            Map<String, String> titleById = new HashMap<>();
            catalog.forEach(b -> titleById.put(b.getId(), b.getTitle()));
            // reasons: nearest consumed book's title
            out = new ArrayList<>();
            for (Book candidate : catalog) {
                if (consumed.contains(candidate.getId())) continue; // never echo what they have
                BookEmbedding e = byBook.get(candidate.getId());
                if (e == null) continue;
                double score = Math.max(0, EmbeddingService.cosine(profile,
                        EmbeddingProvider.fromVectorLiteral(e.getEmbedding())));
                if (score < 0.05) continue;
                String nearestTitle = null;
                double best = -1;
                for (String consumedId : consumed) {
                    BookEmbedding ce = byBook.get(consumedId);
                    if (ce == null) continue;
                    double s = EmbeddingService.cosine(
                            EmbeddingProvider.fromVectorLiteral(ce.getEmbedding()),
                            EmbeddingProvider.fromVectorLiteral(e.getEmbedding()));
                    if (s > best) {
                        best = s;
                        nearestTitle = titleById.getOrDefault(consumedId,
                                books.findById(consumedId).map(Book::getTitle).orElse(null));
                    }
                }
                List<String> because = new ArrayList<>();
                if (nearestTitle != null && best > 0.3) {
                    because.add("close to \"" + nearestTitle + "\" which you engaged with");
                }
                if (candidate.getRatingAvg() >= 4 && candidate.getRatingCount() > 0) {
                    because.add(String.format(java.util.Locale.ROOT,
                            "highly rated here (%.1f★ over %d ratings)",
                            candidate.getRatingAvg(), candidate.getRatingCount()));
                }
                out.add(new RecommendationDto(candidate.getId(), candidate.getTitle(),
                        candidate.getAuthor(), candidate.getSlug(), candidate.getRatingAvg(),
                        candidate.getRatingCount(), Math.round(score * 1000.0) / 1000.0, because));
            }
            out.sort(Comparator.comparingDouble(RecommendationDto::score).reversed()
                    .thenComparing(RecommendationDto::title));
            out = out.stream().limit(max).toList();
        }

        audit.log(AuditAction.AI_RECOMMENDATIONS, "Tenant", tenantId, Map.of(
                "count", out.size(), "basisLength", basis.length()));
        return new RecommendationResult(basis, out, usage);
    }

    private List<RecommendationDto> coldStart(List<Book> catalog, int max) {
        return catalog.stream()
                .sorted(Comparator.comparingDouble(Book::getRatingAvg).reversed()
                        .thenComparing(Comparator.comparingInt(Book::getRatingCount).reversed()))
                .limit(max)
                .map(b -> new RecommendationDto(b.getId(), b.getTitle(), b.getAuthor(), b.getSlug(),
                        b.getRatingAvg(), b.getRatingCount(), 0,
                        b.getRatingCount() > 0
                                ? List.of(String.format(java.util.Locale.ROOT,
                                        "rated %.1f★ over %d ratings in this library",
                                        b.getRatingAvg(), b.getRatingCount()))
                                : List.of("new in this library's catalog")))
                .toList();
    }

    private void bump(Map<String, Double> weights, Map<String, List<String>> reasons,
                      String bookId, double weight, String reason) {
        weights.merge(bookId, weight, Double::sum);
        reasons.computeIfAbsent(bookId, k -> new ArrayList<>()).add(reason);
    }

}
