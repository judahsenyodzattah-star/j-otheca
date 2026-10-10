# J'otheca Rebrand — Report (2026-10-10)

**Scope:** rename *LibraNet* → **J'otheca** across the whole monorepo. This is a
mechanical rebrand, **not** a redesign/rebuild. Business logic, auth, authz,
tenant isolation, RLS, API routes and persisted data are unchanged.

**Branch:** `arena/a775b9a4-j-otheca` · **Commits:** rebrand `16eb6ff` on top of
R1/D1–D3 checkpoint `4c8ba66` (parent `7b2dd73`). **Local only — not pushed.**

## Naming rules applied
- Public/display brand → **J’otheca** (curly apostrophe U+2019, verified bytes `4a e2 80 99`).
- Safe identifier form → **jotheca** (no apostrophe) for packages, filenames,
  config keys, DB names, Docker names, domains, Maven coordinates.
- No all-caps `LIBRANET` and no spaced `libra net` forms existed in the repo.

**Transform (3 ordered `sed` rules, applied to every text file containing the brand):**
1. `s/LibraNetApp/JothecaApp/g`  *(also converts `LibraNetApplication` → `JothecaApplication`)*
2. `s/LibraNet/J’otheca/g`
3. `s/libranet/jotheca/g`

## Files changed — 349 total, by category
| Area | Files | Examples |
|---|---|---|
| backend/src (main, Java `com.jotheca`) | 257 | package rename across 253 `.java`; `application.yml`, `application-dev.yml`, `static/index.html`, `static/app/index.html` |
| backend/src (tests, Java `com.jotheca`) | 18 | all `*FlowTest`, `TenantIsolationTest`, `SecurityHardeningTest`, `TestApi`, `HardeningTest` |
| mobile (Flutter/Android/iOS/web) | 33 | `lib/main.dart`, feature screens, `pubspec.yaml`, Android `build.gradle.kts` + `MainActivity.kt` + `AndroidManifest.xml`, web `index.html`/`manifest.json`, Windows runner |
| docs (README/ADR/audits/academic) | 28 | `docs/academic/**` (proposal, lit review, interim, final report, defense), ADRs, audits, ops runbooks, security checklist |
| scripts (ops/demo) | 6 | `backup.sh`, `restore.sh`, `demo/*.sh`, `load-test.sh` |
| infra (docker/env) | 3 | `docker-compose.yml`, `.env.staging.example`, `env/backend.env.example` |
| backend (build/root) | 2 | `pom.xml` (groupId/artifactId), `Dockerfile` (user + jar name) |
| README.md (root) | 1 | title/brand/credentials |
| .github (CI) | 1 | `ci.yml` (PG db, jar glob, api-docs grep, `ci@…` login) |

`git` detected the moves as renames: **272 renamed+modified** (Java dirs +
`MainActivity.kt` + main class) and **77 modified in place**.

## Important technical renames
- **Java package** `com.libranet` → `com.jotheca` (main **and** test trees moved
  via `git mv`; every `package`/`import` updated). Verified: 0 `com.libranet`
  remain, 0 `import com.libranet`, and **0 package↔directory mismatches** across
  all 271 `.java` files.
- **Main class** `LibraNetApplication` → `JothecaApplication` (file + class +
  `SpringApplication.run(JothecaApplication.class, …)`).
- **Flutter package** `libranet_app` → `jotheca_app` (`pubspec.yaml` name; all
  `package:libranet_app/...` test/lib imports → `package:jotheca_app/...`).
- **Android** applicationId + namespace `com.example.libranet_app` →
  `com.example.jotheca_app` (dir + `MainActivity.kt` moved; `AndroidManifest`
  label `libranet_app` → `jotheca_app`).
- **Maven** groupId `com.libranet` → `com.jotheca`, artifactId/name
  `libranet-backend` → `jotheca-backend` (jar name follows).
- **Container user** `libranet` → `jotheca` (Dockerfile); jar glob updated.

## Compatibility preserved (intentionally NOT changed)
- API routes `/api/v1` and tenant header `X-Library-Id`.
- Historical migrations **V1–V24** (already brand-free; left byte-identical —
  14 migration `.java` files contain no brand token).
- Library UID prefix `LIB-`, invitation code `ASH-2026`, issue numbering `LN-000123`.
- R1 login-timing hardening and D1–D3 session/CORS/refresh fixes (markers
  re-verified present post-rebrand).

## Remaining LibraNet references
**Zero.** Exhaustive sweep for `libranet`, `LibraNet`, `LIBRANET`, `com.libranet`,
`libranet_app`, `LibraNetApp`, `LibraNetApplication`, `libra net`, `libranet.app`,
`libranet.complete`, `libranet-media`, `libranet-dev` → all **0** (excluding
`.git` history, which legitimately retains the old name in past commits).

## Migration / deploy steps requiring attention
No **database** migration is required: schema and persisted data never contained
the brand, and **existing user emails are not altered** (only the dev seeder's
*new* demo accounts and demo login strings changed). The following need operator
action:

1. **Docker volumes (data-detach risk).** `docker-compose.yml` project
   `libranet-complete` → `jotheca-complete` and volumes `libranet-complete_*` →
   `jotheca-complete_*`. A plain `docker compose up` now creates **fresh** volumes,
   detaching existing data. Preserve data by renaming first:
   `docker volume rename libranet-complete_pgdata jotheca-complete_pgdata`
   (repeat per volume: minio, etc.).
2. **Email domain** `@libranet.app` → `@jotheca.app` for demo accounts; changed in
   `DevDataSeeder`, tests, README/DAILY-STARTUP demo creds, `scripts/demo/*`, and
   `static/*` logins **together** so tests and docs stay consistent.
3. **Public base URL / subdomains** `https://libranet.app` → `https://jotheca.app`
   (default in `application.yml`; staging subdomains in `.env.staging.example`).
4. **Postgres defaults** db/user `libranet` → `jotheca` (`application.yml`, CI
   `POSTGRES_DB`); **MinIO** user/secret/bucket `libranet`/`libranet-dev`/
   `libranet-media` → `jotheca` variants.
5. **Maven artifact** now builds `jotheca-backend-*.jar`; `Dockerfile`, `ci.yml`
   and DAILY-STARTUP already updated. Update any *external* deploy tooling that
   still references `libranet-backend-*.jar`.
6. **Android app** is now a distinct app id (`com.example.jotheca_app`): existing
   installs must be **uninstalled then reinstalled** (not upgraded in place).
7. **Mobile sessions**: the Flutter session key `libranet.session.v1` →
   `jotheca.session.v1` (client-side only) invalidates stored sessions once →
   users re-login. No server migration.

## Tests — executed vs not
**Executed in-sandbox (static verification):**
- Exhaustive brand sweep → 0 old-name variants.
- Java package↔directory consistency → 0 mismatches (271 files).
- Import sanity → 0 `import com.libranet`.
- Seed emails vs test logins domain match (`@jotheca.app`) → tests won't break on domain.
- OpenAPI title `"J’otheca API"` matches the CI `grep` assertion.
- Flutter test imports → `package:jotheca_app/...`.
- `manifest.json` parses as valid JSON.
- `git` rename detection clean; R1/D1–D3 markers + tenant-isolation test intact.

**NOT executed (toolchain + download hosts blocked in this sandbox):**
`mvn verify` and `flutter test`/`flutter analyze`. Run locally on Windows:

```powershell
# Backend — compile + full test suite (incl. R1 timing test + tenant isolation)
cd backend
mvn clean verify

# Mobile — dependency fetch, analyzer, unit tests
cd ..\mobile
flutter pub get
flutter analyze
flutter test
```

The critical regression guard for the preserved R1 fix is the **timing** assertion
in `com.jotheca.identity.SecurityHardeningTest`
(`unknownAccountLoginStillVerifiesPasswordToEqualizeTiming`) — confirm it still
passes under `mvn verify`.

## Risks / notes
- Android launcher label kept as `jotheca_app` (parallel to the old
  `libranet_app`) to preserve the repo's naming convention; switch to the
  display name **J’otheca** only if a launcher-brand change is desired.
- The rebrand changed identifiers and strings only; no control flow, auth,
  authz, tenant-context enforcement or RLS behavior was touched.
- No commit was pushed and no deploy was modified destructively — per instruction.
