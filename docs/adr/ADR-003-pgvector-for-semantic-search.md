# ADR-003: pgvector over a separate vector database

**Status:** Accepted (2026-08-10) · **Phase:** planned (10)

## Decision
Embeddings live in PostgreSQL via `pgvector`, colocated with tenant data. Every ANN query carries
a mandatory `tenant_id` predicate enforced by the same engine already isolating tenants.

## Rationale
The brief's Chroma instance would require collection-per-tenant discipline and an independent
leak surface (spec §33: vector search must NEVER leak between libraries). pgvector removes that
class of risk and one infrastructure dependency. A `VectorStore` interface keeps a future
dedicated vector DB possible if ANN scale demands it.

## Consequences
+ Tenant filter + vector similarity in ONE query; backups consistent with the catalog.
− Large-scale ANN tuning comes later; interface isolates the swap.
