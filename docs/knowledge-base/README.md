# J’otheca Knowledge Base

This is the curated, permanent knowledge base for J’otheca, formerly LibraNet. It separates current evidence and approved product decisions from historical reports, academic submissions, dated audits, and operational runbooks.

## Status vocabulary

- **Verified** — observed in source plus an executed build, test, migration, startup, or endpoint check, with the environment stated.
- **Implemented-but-unverified** — present in source or documented as implemented, but not covered by the current verification evidence.
- **Proposed** — approved or candidate future behavior not yet implemented; product approval is required before implementation.
- **Pending import** — supplied authority exists outside this repository and has not yet been copied into the knowledge base.

## Current documents

- [Product and system overview](current-system/overview.md)
- [Backend architecture](current-system/backend-architecture.md)
- [Flutter architecture](current-system/flutter-architecture.md)
- [Security, authorization, and tenant isolation](security/tenant-isolation-and-authorization.md)
- [Infrastructure and verification baseline](current-system/infrastructure-and-verification.md)
- [Reusable capabilities](current-system/reusable-capabilities.md)
- [E-Discover reuse and gap analysis](current-system/e-discover-reuse-and-gaps.md)
- [Architecture decisions index](decisions/README.md)
- [Product traceability](traceability/README.md)
- [Safe roadmap and phase gates](roadmap/README.md)
- [Pending E-Discover blueprint import](pending-import/e-discover-blueprint.md)
- [Canonical E-Discover blueprint v1.0](e-discover/blueprint-v1.0.md)
- [E-Discover decision register](e-discover/decision-register.md)
- [E-Discover user journeys](e-discover/user-journeys.md)
- [E-Discover acceptance criteria](e-discover/acceptance-criteria.md)
- [E-Discover open questions](e-discover/open-questions.md)
- [E-Discover traceability matrix](traceability/e-discover-matrix.md)
- [E-Discover roadmap](roadmap/e-discover-roadmap.md)
- [Phase 5A implementation status](e-discover/phase-5a-status.md)
- [Phase 5B.1 library discovery status](e-discover/phase-5b1-status.md)
- [Phase 5B.5 global discovery policy and ISBN foundation](e-discover/phase-5b5-status.md)
- [Phase 5B.7 global discovery projection schema foundation](e-discover/phase-5b7-status.md)

## Source hierarchy

1. Explicit product-owner decisions and the imported E-Discover blueprint, once supplied.
2. This knowledge base for curated current-state facts and approved decisions.
3. Source code, migrations, tests, and executed verification logs as evidence.
4. Existing ADRs, audits, academic documents, and runbooks as preserved references.

When sources disagree, do not silently reconcile them. Record the conflict and seek approval.

## Preserved references

- [Root README](../../README.md)
- [Architecture and roadmap](../ARCHITECTURE-AND-ROADMAP.md)
- [Arena progress record](../ARENA-PROGRESS.md)
- [System analysis](../SYSTEM-ANALYSIS-2026-10-10.md)
- [UX / role / AI audit specification](../audits/AUDIT-SPEC-UX-ROLE-AI.md)
- [Academic documentation](../academic/README.md)
