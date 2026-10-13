# ADR-001: Modular monolith over microservices

**Status:** Accepted (2026-08-10) · **Phase:** 0

## Context
J’otheca targets many small tenants (author/personal libraries) alongside institutions.
The V1 scaling unit is tenant count, not datastore throughput.

## Decision
One Spring Boot application, packaged **by feature** (`identity`, `tenancy`, `membership`,
`platform`, `catalog`, …) under `com.jotheca`. Module boundaries are strict (no cross-package
entity leakage; DTOs at the edge) so any module can later be extracted into a service.

## Consequences
+ One deployable, one migration story (Flyway), one test harness (isolation suite sees everything).
− Discipline is manual: package-private by default, review for cross-module imports.
Revisit only when a module earns its own scaling or release cadence.
