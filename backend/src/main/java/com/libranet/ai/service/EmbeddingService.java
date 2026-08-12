package com.libranet.ai.service;

import com.libranet.ai.embed.EmbeddingProvider;
import com.libranet.ai.model.BookEmbedding;
import com.libranet.ai.repo.BookEmbeddingRepository;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.catalog.repo.CategoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Embedding lifecycle (Phase 10): builds each book's index text (title · subtitle · authors ·
 * subjects · category name · description · publisher · year), embeds it through the active
 * provider, and re-embeds only when the text hash changed. Lazy self-healing: a semantic
 * search first refreshes any stale/missing embeddings for its OWN tenant — never globally.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final BookEmbeddingRepository embeddings;
    private final BookRepository books;
    private final CategoryRepository categories;
    private final EmbeddingProvider provider;

    public EmbeddingService(BookEmbeddingRepository embeddings, BookRepository books,
                            CategoryRepository categories, EmbeddingProvider provider) {
        this.embeddings = embeddings;
        this.books = books;
        this.categories = categories;
        this.provider = provider;
    }

    /** The indexed document for a book (category name resolved; stable field order). */
    public String textOf(Book book, String categoryName) {
        StringBuilder sb = new StringBuilder();
        append(sb, book.getTitle());
        append(sb, book.getSubtitle());
        append(sb, book.getAuthor());
        append(sb, book.getCoAuthors());
        append(sb, book.getSubjects());
        append(sb, categoryName);
        append(sb, book.getDescription());
        append(sb, book.getPublisher());
        append(sb, book.getPublishedDate());
        append(sb, book.getLanguage());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) sb.append(' ').append(s.trim());
    }

    public static String hashOf(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Refresh one book's embedding (called from catalog create/update hooks; failures are soft). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void refreshBook(String tenantId, String bookId) {
        try {
            books.findById(bookId).ifPresent(book -> upsert(book, categoryNames(tenantId)));
        } catch (Exception e) {
            log.warn("embedding refresh failed for book {}: {}", bookId, e.toString());
        }
    }

    /**
     * Ensure every non-deleted book of the tenant has a fresh embedding, then hand back the
     * tenant's rows. Self-heals after seeds, imports and edits. Tenant-scoped by design.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public List<BookEmbedding> freshEmbeddingsOf(String tenantId) {
        Map<String, String> categoryNames = categoryNames(tenantId);
        List<Book> all = books.findAll((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
        for (Book book : all) {
            upsert(book, categoryNames);
        }
        return embeddings.findByTenantId(tenantId);
    }

    /** Full re-embed (staff trigger); returns the row count. */
    @Transactional
    public int rebuildTenant(String tenantId) {
        embeddings.deleteByTenantIdAndBookIdIn(tenantId,
                embeddings.findByTenantId(tenantId).stream().map(BookEmbedding::getBookId).toList());
        return freshEmbeddingsOf(tenantId).size();
    }

    /** Drop embeddings for books that disappeared (embeddings are derived data — may be removed). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void removeBook(String tenantId, String bookId) {
        embeddings.deleteByTenantIdAndBookIdIn(tenantId, List.of(bookId));
    }

    private void upsert(Book book, Map<String, String> categoryNames) {
        String text = textOf(book, categoryNames.get(book.getCategoryId()));
        String hash = hashOf(text);
        BookEmbedding row = embeddings.findByTenantIdAndBookId(book.getTenantId(), book.getId()).orElse(null);
        if (row != null && row.getTextHash().equals(hash) && row.getModel().equals(provider.modelName())) {
            return; // fresh
        }
        float[] vector = provider.embed(text);
        if (row == null) {
            row = new BookEmbedding();
            row.setTenantId(book.getTenantId());
            row.setBookId(book.getId());
        }
        row.setModel(provider.modelName());
        row.setDims(vector.length);
        row.setEmbedding(EmbeddingProvider.toVectorLiteral(vector));
        row.setTextHash(hash);
        embeddings.save(row);
    }

    private Map<String, String> categoryNames(String tenantId) {
        try {
            return categories.findByTenantIdOrderBySortOrderAscNameAsc(tenantId).stream()
                    .collect(Collectors.toMap(c -> c.getId(), c -> c.getName(), (a, b) -> a, HashMap::new));
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Cosine similarity assuming both vectors came from an L2-normalizing provider. */
    public static double cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) return 0;
        double d = 0;
        for (int i = 0; i < a.length; i++) d += a[i] * b[i];
        return d;
    }
}
