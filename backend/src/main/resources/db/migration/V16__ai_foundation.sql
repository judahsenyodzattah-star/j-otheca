-- V16 — AI foundation (Phase 10). Portable SQL (PG 16 + H2-PG-mode).
--
-- Embeddings are stored as pgvector text literals ([0.1,-0.2,...]) in a portable TEXT column.
-- On real Postgres, V17 (Java migration) creates the pgvector extension + an HNSW index over
-- (embedding::vector) and searches can switch to the native ANN backend; on H2/dev the Java
-- cosine backend scores the same literals. Either way: every query is WHERE tenant_id = ? —
-- vector search is tenant-isolation layer 9 of 9, and there is no cross-tenant query path at all.

CREATE TABLE book_embeddings (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(36) NOT NULL REFERENCES libraries(id),
    book_id     VARCHAR(36) NOT NULL,                 -- unique per tenant; never reused after soft ops
    model       VARCHAR(60) NOT NULL,                 -- embedder identity, e.g. local-hash-256 / openai:text-embedding-3-small
    dims        INT         NOT NULL,
    embedding   TEXT        NOT NULL,                 -- pgvector literal "[f,f,...]"; portable
    text_hash   VARCHAR(64) NOT NULL,                 -- SHA-256 of the source text — stale embedding detection
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    CONSTRAINT uq_book_embedding UNIQUE (tenant_id, book_id)
);
CREATE INDEX idx_book_embeddings_tenant ON book_embeddings(tenant_id);

-- AI call metering (plan limit aiCallsMonthly, spec §16). One row per tenant/feature/month.
CREATE TABLE ai_usage_counters (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(36) NOT NULL REFERENCES libraries(id),
    feature     VARCHAR(30) NOT NULL,   -- SEMANTIC_SEARCH | RECOMMENDATIONS | ASSISTANT | DEMAND_INSIGHTS
    month_key   CHAR(7)     NOT NULL,   -- e.g. '2026-08'
    calls       INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    CONSTRAINT uq_ai_usage UNIQUE (tenant_id, feature, month_key)
);
CREATE INDEX idx_ai_usage_tenant ON ai_usage_counters(tenant_id, month_key);

-- Member search telemetry for demand insights. Stores the normalized query + hit count —
-- NEVER the raw user identity beyond an optional user id (privacy by default).
CREATE TABLE search_events (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(36)  NOT NULL REFERENCES libraries(id),
    query        VARCHAR(300) NOT NULL,              -- normalized (lowercase, trimmed) member search text
    result_count INT          NOT NULL,
    user_id      VARCHAR(36),                        -- nullable; not joined anywhere in insights
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    created_by   VARCHAR(255),
    updated_by   VARCHAR(255)
);
CREATE INDEX idx_search_events_tenant ON search_events(tenant_id, created_at);
