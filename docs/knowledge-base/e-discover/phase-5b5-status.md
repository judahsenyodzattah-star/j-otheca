# Phase 5B.7 Global Discovery Projection Schema Foundation

**Status:** Implemented internal schema foundation; global discovery endpoint and synchronization remain disabled.

## Phase 5B.7 implementation

- Added Flyway V28 with a dedicated `discovery` schema containing editions, holdings, and private source mappings.
- A library/edition has one holding, while multiple source books may map to that holding. Source identity is unique only by `(source_library_id, source_book_id)`; there is intentionally no `(holding_id, source_library_id)` uniqueness constraint.
- Added inactive/revoked state, source sync state, metadata conflict state, source version, and projection version fields for future fail-closed reconciliation.
- Conflicting source metadata is represented as `CONFLICTED` with a required reason and must not be attached to an active holding by a future synchronizer.
- Added internal JPA entities and repositories only. No population, backfill, worker, endpoint, public view, role, grant, security-definer function, or DTO was added.
- Added `app.discovery.global-enabled`, defaulting to `false`. No code path enables or consumes global discovery yet.

## Implemented

- Added Flyway V27 policy columns and constraints.
- Existing libraries default to `MEMBERS_ONLY` for global catalog discovery.
- Existing libraries default to `PRESENCE` holdings visibility.
- Existing books default to `global_discoverable = false`.
- Added `CatalogVisibility` and `HoldingsVisibility` entity enums.
- Added `Book.globalDiscoverable` and corresponding `Library` policy mappings.
- Added a pure discoverability evaluator requiring active, directory-visible, published, non-members-only, explicitly opted-in records.
- Added validated ISBN-10/ISBN-13 normalization to canonical ISBN-13.
- Added conservative edition keys: validated ISBNs may group; missing ISBN records remain source-specific.
- Preserved existing per-library public catalog behavior because `PublicCatalogService` does not consult the new fields.

## Migration compatibility

Migration `V27__global_discovery_policy.sql` follows the existing sequence after V26. It adds columns with conservative defaults, validates allowed enum values through database checks, and adds indexes for future discovery queries.

No global endpoint, projection population, role, security-definer function, worker, administrator workflow, public view, or Flutter integration was added.

The PUBLIC product rule that published books are discoverable by default is intentionally not activated by this foundation. Until the controlled administrator opt-in workflow exists, the evaluator requires `global_discoverable = true` for both PUBLIC and PARTIALLY_PUBLIC catalogs. This is the approved safety refinement for existing data and prevents accidental publication.

## Verification boundary

- H2/application tests can verify the evaluator, ISBN validation, entity mappings, feature-flag default, and migration compatibility.
- PostgreSQL-specific schema creation, cross-schema foreign keys, constraint enforcement, index creation, role privileges, RLS coverage, connection-pool behavior, and migration execution remain unverified until Docker/Testcontainers is available.
- V28 intentionally does not create roles, grants, security-definer functions, or public views; their security behavior is therefore not yet testable in this phase.
- This migration does not change PostgreSQL RLS policies or tenant authorization.
