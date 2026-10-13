package com.jotheca.ai.repo;

import com.jotheca.ai.model.BookEmbedding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookEmbeddingRepository extends JpaRepository<BookEmbedding, String> {

    Optional<BookEmbedding> findByTenantIdAndBookId(String tenantId, String bookId);

    /** The ANN candidate set — ALWAYS tenant-pinned (isolation layer 9). */
    List<BookEmbedding> findByTenantId(String tenantId);

    long countByTenantId(String tenantId);

    void deleteByTenantIdAndBookIdIn(String tenantId, java.util.Collection<String> bookIds);

    /**
     * Native pgvector ANN path (real Postgres only; selected by app.ai.search.backend=pgvector).
     * tenant_id predicate precedes the vector ordering — the ANN index is used within the
     * tenant's partition of the table, never across tenants.
     */
    @Query(value = "SELECT book_id FROM book_embeddings WHERE tenant_id = :tenantId "
            + "ORDER BY embedding::vector <=> CAST(:queryVector AS vector) LIMIT :maxResults",
            nativeQuery = true)
    List<String> nearestBookIdsNative(@Param("tenantId") String tenantId,
                                      @Param("queryVector") String queryVector,
                                      @Param("maxResults") int maxResults);
}
