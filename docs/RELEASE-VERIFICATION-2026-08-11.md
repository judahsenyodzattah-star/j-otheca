# Release verification — 2026-08-11

Checklist executed against `docs/RELEASE-CHECKLIST.md`, dev instance (H2, port 8080),
jar `libranet-backend-0.1.0-SNAPSHOT` (sha: built 13:49 UTC), all 19 Flyway migrations
applied on boot.

**Verdict: ✅ GO for staging.** Production go requires the three staging/CI items listed
at the bottom — everything verifiable in this environment passes.

## 1. Code & tests — ✅
- `mvn test`: **120/120, 0 failures** (13 suites) — includes `TenantIsolationTest` (33) and `HardeningTest` (8).
- No `System.out.println`/`printStackTrace` anywhere in `src/main` or `src/test` (grep).
- No TODO/FIXME markers (grep hits were "XXXXXX" ID-shape examples in comments).
- `backup.sh`, `restore.sh`, `load-test.sh`, demo scripts: `bash -n` clean.
- ⚠ *Flutter analyze/build — no SDK in this sandbox; CI gate (deferred, see below).*

## 2. Database — ✅ (H2) / ⚠ one PG item
- V1…V19 validate + apply on an empty schema at every boot (dev reseeds constantly).
- **RLS static audit: 25/25 tenant tables carry policies** (V2/V6/V8/V10/V12/V15/V17);
  `libraries` is the tenant root by design (no `tenant_id`; access via service guards).
- V18 indexes present (`HardeningTest.phase11IndexesExist` — 5/5).
- ⚠ *V19 `pg_trgm` GIN indexes are PG-only (no-op on H2) — verify on staging Postgres.*

## 3. Security — ✅
- Live headers: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'`,
  `Permissions-Policy: camera=(), microphone=(), payment=(), usb=(), bluetooth=()`.
- Live guards: tenant endpoint w/o token → 400; w/o `X-Library-Id` → 400;
  patron → `/tenant/ai/usage` 403; **cross-tenant book ID → 404** (isolation by invisibility).
- Lockout 423 / MFA challenge separation / session revocation: covered by suites (P8, 86 tests).
- Secrets: source scan clean (no keys/tokens in git); `application.yml` reads
  `APP_JWT_SECRET`, `REDIS_HEALTH_ENABLED` etc. from env; AI key only via `AI_OPENAI_API_KEY`.
- Map: `docs/SECURITY-PENTEST-CHECKLIST.md`.

## 4. Operations — ✅ scripts/health / ⚠ drill on staging
- `/actuator/health` UP (Redis indicator correctly masked by `REDIS_HEALTH_ENABLED=false`).
- Runbooks RB-01…RB-08 present (`docs/ops/RUNBOOKS.md`); backup policy documented
  (`docs/ops/BACKUP-AND-RESTORE.md`, RPO ≤15 min / RTO ≤1 h).
- ⚠ *`pg_restore --list` + full restore drill need the Postgres/MinIO stack (no Docker here)
  — schedule drill day-1 on staging; scripts are `bash -n`-verified and reviewed.*

## 5. Contracts & performance — ✅
- `demo-p11.sh` **6/6**: discovery + public detail `Cache-Control: max-age=60, public`;
  invitation preview `no-store`; tenant catalog never public-cacheable; api-docs contract.
- api-docs: `ApiError` envelope schema present with all 6 fields; login documents
  400/401/423/429; AI search summary + 400/401/403 (no 423 leak); discovery has no 401.
- Load baseline (this boot, 40 req/endpoint, budgets in `docs/PERF-BASELINE.md`):
  catalog search p95 **23.7 ms** (≤150), discovery p95 **10.7 ms** (≤80),
  public page p95 **14.9 ms** (≤80), AI search p95 **34.5 ms** (≤400). All within budget.
- `demo-p10.sh` **14/14** (AI regression) on the fresh instance.

## 6. Product polish — ✅
- Seed data live: 4 libraries (pentecost, accra-central, ashesi, auntie-esi-writes).
- WCAG 2.1 AA audit signed: `docs/ACCESSIBILITY-AUDIT.md`.
- README phase table 0–11 all ✅; roadmap rows 0–11 ✅.

## Staging/CI prerequisites before production go — updated same day, staging package landed
1. Flutter analyze + test + **`flutter build web`** — now enforced by CI job `flutter` on every push.
2. `pg_dump`/`pg_restore` restore drill on the staging cluster + blob tar round-trip
   (scripts + runbook ready: `scripts/backup.sh`, `scripts/restore.sh`, `docs/ops/STAGING-DEPLOY.md` §4).
3. Real-Postgres proof on every push — CI job **`staging-migration-smoke`** boots the `staging`
   profile against pgvector/pg16 + Redis + mailpit and asserts health, HTTP smoke, and the PG-only
   artifacts: HNSW index present, 4 trigram GIN indexes, ≥25 `*_tenant_isolation` RLS policies with
   RLS actually enabled. Deploy itself: `docker compose --profile full up` with
   `infra/.env` (template: `infra/.env.staging.example`).
