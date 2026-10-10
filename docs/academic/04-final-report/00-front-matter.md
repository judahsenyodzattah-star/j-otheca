# J’otheca — A Secure Multi-Tenant SaaS Platform for Library Management with AI-Assisted Discovery

*Pentecost University*

*[DEPARTMENT]*

*A dissertation submitted to the Department of [DEPARTMENT], Pentecost University, in partial
fulfilment of the requirements for the award of the degree of BSc Information Technology.*

**Student:** Senyo-Dzattah Judah Agbemavor (PUIT/23110016)

**Supervisor:** [SUPERVISOR NAME], [SUPERVISOR TITLE]

**[MONTH, YEAR]**

---

## Declaration

I declare that this dissertation is my own original work and that it has not been submitted,
in whole or in part, for any other degree. All sources are acknowledged.

Signed: …………………………  Date: ………………

---

## Abstract

Small libraries are priced or skilled out of professional library software: open-source
integrated library systems assume one installation per institution and an administrator to
run it, while hosted offerings are closed and per-institution priced. This dissertation
presents **J’otheca**, a multi-tenant software-as-a-service platform in which many libraries
share one deployment as isolated tenants — covering identity and membership, plan-metered
subscriptions with billing on African payment rails, physical-first circulation with barcoded
desk flows, author-owned personal libraries with public book pages, media, ratings and
moderation, and an AI discovery layer (semantic search with natural-language year filters,
per-member recommendations, a guarded librarian assistant and acquisition demand insights).

The central engineering contribution is treating **tenant isolation as a tested property**:
isolation is enforced across nine architectural layers — from HTTP tenant resolution and
service guards through Hibernate filters, Postgres row-level security on every tenant table,
tenant-prefixed object storage and tenant-pinned vector search — and is verified by a
continuously evolving suite of 33 cross-tenant negative tests inside a 120-test automated
regression suite, with a CI job that re-proves the database-level policies on real Postgres
at every change. Plan limits (members, books, branches, storage, AI calls) are data, changed
by operators without deploys; AI usage is metered per tenant per feature per month, and the
audit trail provably never records secrets or question text.

Measured results on the reference deployment show all 120 tests passing and hot-endpoint
latencies far inside budget (tenant catalogue search p95 ≈ 30 ms; AI semantic search
p95 ≈ 50 ms), with accessibility audited to WCAG 2.1 AA and operations documented through
runbooks, a backup/restore drill and a release checklist. The dissertation concludes that
the conjunction left open by the literature — library-grade circulation semantics, auditable
multi-tenancy, and tenant-safe AI discovery — is realisable in a single open platform.

**Keywords:** multi-tenancy, SaaS, library management, row-level security, tenant isolation
testing, semantic search, pgvector, plan metering, Flutter, Spring Boot.

---

## Acknowledgements

[Thank your supervisor, department, and anyone else — write this last, write it honestly.]

---

## Table of contents

1. Introduction
2. Literature review
3. Requirements and analysis
4. Design
5. Implementation
6. Testing and verification
7. Evaluation
8. Conclusion and future work
9. References
10. Appendices
