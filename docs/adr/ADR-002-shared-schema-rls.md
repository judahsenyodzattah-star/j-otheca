# ADR-002: Tenancy = shared schema + tenant_id, backstopped by Postgres RLS

**Status:** Accepted (2026-08-10) · **Phase:** 2

## Options considered
1. Database-per-tenant — clean isolation, operationally unmanageable at SaaS tenant counts.
2. Schema-per-tenant — still heavy; Flyway over N schemas slows deploys.
3. **Shared schema + `tenant_id` discriminator** with: server-side tenant resolution from a
   validated `X-Library-Id` header, `TenantContext`, Hibernate `@Filter` on all tenant entities,
   and **Postgres Row-Level Security** armed per-transaction via `SET LOCAL app.tenant_id`
   (V2__RlsPolicies + TenantAwareDataSource).

## Decision
Option 3, with isolation treated as a *testable invariant*: `TenantIsolationTest` (Library A
caller vs Library B resources) runs in CI and blocks merges; RLS is the last line when the app
layers make a mistake.

## Consequences
+ Cheap to onboard tenant #10,000; one flyway history.
− Every tenant entity contractually extends `TenantScopedEntity`; code review checklist enforces it.
RLS is inactive on the H2 dev profile by design — documented in the README.
