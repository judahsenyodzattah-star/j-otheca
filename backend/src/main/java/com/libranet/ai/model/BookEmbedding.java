package com.libranet.ai.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Per-book embedding vector (Phase 10). The vector is stored as a pgvector text literal
 * ([0.1,-0.2,...]) so the schema is portable (TEXT on H2; V17 adds an HNSW index over
 * (embedding::vector) on real Postgres). Rows are refreshed when the book's text-hash
 * changes; deleting a book removes its embedding (embeddings are derived data, not records).
 */
@Entity
@Table(name = "book_embeddings")
public class BookEmbedding extends TenantScopedEntity {

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Column(nullable = false, length = 60)
    private String model;

    @Column(nullable = false)
    private int dims;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "text_hash", nullable = false, length = 64)
    private String textHash;

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDims() { return dims; }
    public void setDims(int dims) { this.dims = dims; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public String getTextHash() { return textHash; }
    public void setTextHash(String textHash) { this.textHash = textHash; }
}
