# Chapter 10 — Appendices

## Appendix A — API surface (versioned excerpt)

Complete, current, example-rich API documentation is generated at `/v3/api-docs`
(Swagger UI at `/swagger-ui.html` in dev/staging with `SWAGGER_ENABLED=true`). Every
operation documents the error codes it can produce against the shared `ApiError` envelope.
Domain quick maps live in the repository README (per phase).

## Appendix B — Repository guide

```
backend/   Spring Boot app — modules: identity, tenancy, membership, onboarding, billing,
           catalog, circulation, publishing, media, analytics, ai, platform, common
mobile/    Flutter app (Android/iOS/web): auth, discovery, home, catalog, ai, public pages
infra/     docker-compose.yml, .env.staging.example
docs/      architecture+roadmap, ops (backup/restore, runbooks, staging deploy),
           security pentest map, accessibility audit, perf baseline, release checklist,
           academic pack (this dissertation set)
scripts/   bootstrap-tools, backup.sh, restore.sh, load-test.sh
```

## Appendix C — Reproduction commands

```bash
# full automated verification (120 tests; isolation suite included)
cd backend && mvn test
# boot + scripted live demos
java -jar backend/target/libranet-backend-*.jar --spring.profiles.active=dev
bash demo-p10.sh   # AI: 14 checks                  bash demo-p11.sh  # hardening: 6 checks
scripts/load-test.sh                                   # perf baselines (budgets in docs/PERF-BASELINE.md)
```

## Appendix D — Database migrations V1–V19 (summary)

V1 init (users/libraries/memberships/audit/notifications); V2 RLS core; V3 onboarding &
billing; V4 seed plans; V5 catalog; V6 catalog RLS; V7 circulation; V8 circulation RLS;
V9 publishing; V10 publishing RLS; V11 media & reviews; V12 media RLS; V13 security
(lockout columns, sessions); V14 billing/analytics (storage ledger); V15 billing RLS; V16 AI
foundation (embeddings/usage/search events); V17 AI RLS + pgvector ext + HNSW; V18
performance indexes; V19 trigram GIN (PG-only). Extend-only by rule.

## Appendix E — Release & verification records

- `docs/RELEASE-CHECKLIST.md` — the go/no-go gate.
- `docs/RELEASE-VERIFICATION-2026-08-11.md` — the executed verification (all gates measured).
- `docs/SECURITY-PENTEST-CHECKLIST.md`, `docs/ACCESSIBILITY-AUDIT.md`,
  `docs/PERF-BASELINE.md` — supporting audits.

## Appendix F — Ethics & data statement

The platform stores membership PII and behavioural telemetry under tenant keys, minimises
free-text sensitive data (AI audit stores no question text), supports anonymous review
display under per-library policy, and never exposes avatars or private data on the public
plane. No real patron data was used in testing; all personas are seeded fixtures.
