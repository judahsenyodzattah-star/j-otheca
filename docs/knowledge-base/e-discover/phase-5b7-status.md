# Phase 5B.7 Global Discovery Projection Schema Foundation

**Status:** Implemented internal schema foundation; PostgreSQL verification coverage prepared but not locally executed. Global discovery remains disabled.

## Implemented

- V28 adds the `discovery` schema with editions, holdings, and private source mappings.
- Multiple source books can map to one holding; source identity is unique only by `(source_library_id, source_book_id)`.
- V28 adds inactive/revoked state, conflict state, source version, and projection version fields.
- V27 and V28 add internal entities and repositories only. No projection population, backfill, worker, endpoint, public view, role, grant, security-definer function, DTO, or administrator workflow was added.
- `app.discovery.global-enabled` remains disabled by default.

## Phase 5B.10 verification coverage

- Added disposable PostgreSQL 16/Testcontainers checks for V27 defaults, checks, indexes, V28 catalog metadata, cross-schema constraints, duplicate source identity rejection, composite holding/edition rejection, runtime-role denial before explicit discovery grants, and V26 forced RLS.
- The CI RLS gate now requires both the existing tenant-isolation report and the discovery schema report, with zero skips, failures, or errors.
- The real-PostgreSQL staging smoke assertion now checks Flyway V28 and the three discovery tables.

## Verification boundary

- H2 tests and compilation do not verify PostgreSQL behavior.
- Docker, PostgreSQL 16, Testcontainers execution, PostgreSQL privilege behavior, V27/V28 migration execution against PostgreSQL, and the CI gate remain unverified locally because no local Docker runtime or PostgreSQL installation is available.
- The Java schema test creates only disposable data inside its throwaway Testcontainers database. Role assertions run through the existing disposable restricted-role smoke environment; no roles or grants are added to application migrations or persistent databases.
- V28 intentionally does not create production roles, grants, security-definer functions, or public views. Those remain security gates before global discovery can be enabled.
