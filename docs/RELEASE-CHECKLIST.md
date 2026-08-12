# Release checklist (Phase 11) — go / no-go gate

Run top to bottom. Any ❌ is a no-go until fixed.

## 1. Code & tests
- [ ] `mvn test` green — all suites incl. `TenantIsolationTest` (9-layer isolation, currently 33 cases) and `HardeningTest` (cache headers, OpenAPI errors, V18 indexes)
- [ ] No `System.out` debug prints in `src/test` or `src/main` (grep)
- [ ] Flutter analyze clean (CI) — app compiles for mobile + web

## 2. Database
- [ ] Migrations V1…V19 apply cleanly on an empty cluster AND on a copy of production
- [ ] Every new table since V2 has RLS (V2/V6/V8/V10/V12/V15/V17) — no unprotected tenant table
- [ ] V18 indexes present (`HardeningTest.phase11IndexesExist`); V19 trigram on PG verified on staging

## 3. Security (map: `docs/SECURITY-PENTEST-CHECKLIST.md`)
- [ ] Cross-tenant IDs → 404 everywhere; SUPER_ADMIN cannot do library operations
- [ ] Lockout 423 after 10 failures; MFA challenge tokens unusable as access tokens
- [ ] Secrets only via env (`JWT_SECRET`, `AI_OPENAI_API_KEY`, Paystack keys); nothing in git
- [ ] Rate limits on `/auth/**`; Redis fail-open reviewed (RB-03)

## 4. Operations
- [ ] `scripts/backup.sh` scheduled (nightly) + verified `pg_restore --list`
- [ ] Last quarterly restore drill signed off (`docs/ops/BACKUP-AND-RESTORE.md`)
- [ ] Runbooks RB-01…RB-08 reviewed by on-call
- [ ] Dashboards reachable: `/actuator/health` UP with `REDIS_HEALTH_ENABLED` set correctly for the env

## 5. Contracts & performance
- [ ] `/v3/api-docs` regenerates with the `ApiError` envelope on every operation (HardeningTest)
- [ ] Load baseline re-run (`scripts/load-test.sh`) and within budget vs `docs/PERF-BASELINE.md`
- [ ] Public endpoints still advertise `Cache-Control: public, max-age=60`; invitation previews `no-store`; private endpoints never `public`

## 6. Product polish
- [ ] WCAG AA items in `docs/ACCESSIBILITY-AUDIT.md` remain ✅ after any theme change
- [ ] Seed/demo data loaded for the demo tenants
- [ ] README phase table + roadmap row updated for the shipped phase
