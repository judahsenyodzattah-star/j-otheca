# Access and Execution Report — 2026-10-10

_Gate check performed before any further audit or implementation. Every statement below is
backed by a command actually executed in this environment. Nothing is assumed._

## Verdict (one line)
I can **read the entire repository** and **modify files in the working copy**, but I **cannot
run any of the project's builds, tests, or services** — the JDK, Maven, Flutter/Dart, Docker,
PostgreSQL, Redis and MinIO are all absent, and Maven Central / Gradle / pub.dev are
network-blocked. Work is therefore **static analysis + Python-based structural checks**;
anything runtime-dependent is **unverified** here and must be confirmed with the exact
commands in §8.

## 1. Read access — YES (full)
| Area | Evidence (actual counts) |
|---|---|
| Backend Java (main) | 263 `.java` under `backend/src/main/java` (incl. `com.jotheca/**`, `db/migration/**`) |
| Backend Java (tests) | 18 `.java` under `backend/src/test/java/com/jotheca/**` |
| DB migrations | 14 `.sql` (`resources/db/migration`) + 10 Java Flyway (`java/db/migration`, RLS) |
| Flutter source | 37 `.dart` (`mobile/lib`) + 8 test `.dart` (`mobile/test`) |
| Config / CI / docs | `pom.xml`, `application*.yml`, `.github/workflows/ci.yml`, `infra/*`, 42 `docs/*.md` |
All inspected directly this session (auth, tenant/RLS, permissions, payments, AI, Flutter).

## 2. Write access — YES (working copy), with one caveat
- Repo root: `/home/user/j-otheca`; branch `arena/a775b9a4-j-otheca`.
- Working copy is **writable**; my edits persist — current `git status` shows **356 changed
  files** (the full J'otheca rebrand + R1/D1–D3 + this session's analysis/audit docs).
- **Caveat (observed repeatedly):** git **history resets between turns to `7b2dd73`** — local
  commits I make do **not** survive to later turns, but **working-tree files do**. So the
  durable artifact is the working tree, not commits.
- **Per your instruction, I will not `git commit`/`git push` from here on without explicit
  authorization.** (Earlier this session I made local checkpoint commits; none were pushed,
  and they no longer exist after the history resets anyway.)

## 3. Toolchain — NOT AVAILABLE (verified by `command -v` + version probes)
| Tool | Status |
|---|---|
| `java` / `javac` (JDK 21) | **NOT INSTALLED** |
| `mvn` (Maven) | **NOT INSTALLED** (and no `backend/mvnw` wrapper exists) |
| `gradle` | NOT INSTALLED (`mobile/android/gradlew` exists but unusable without JDK/Gradle) |
| `flutter` / `dart` | **NOT INSTALLED** |
| `docker` / `docker compose` | **NOT INSTALLED** (daemon unavailable) |
| `psql`, `redis-server` | NOT INSTALLED |
| `python3` 3.11.2 | AVAILABLE |
| `git` 2.39.5 | AVAILABLE |
Build config actually used by the project: backend = **Maven `pom.xml`** (Java 21, Spring Boot
3.5); mobile = **Flutter, SDK `^3.5.0`**.

## 4. Services — NONE running / startable here
Ports probed on 127.0.0.1 — **all closed**: 5432 (Postgres), 9000/9001 (MinIO), 6379 (Redis),
8080 (app), 8025 (mailpit). Docker is unavailable, so no service can be started. (For
reference, **CI** provisions these: `pgvector/pgvector:pg16`, `redis:7-alpine`, `axllent/mailpit`.)

## 5. Network egress — why the toolchain can't be bootstrapped
| Host | Result |
|---|---|
| `repo1.maven.org` (Maven Central) | **BLOCKED** (TLS rc=35) |
| `services.gradle.org` | **BLOCKED** |
| `pub.dev` (Flutter packages) | **BLOCKED** |
| `pypi.org`, `registry.npmjs.org`, `github.com` | reachable (200) |
→ Cannot install a JDK/Maven/Flutter or download dependencies. This is an environment limit,
not a code defect.

## 6. Which tests need which dependency (from the actual build config)
- **`mvn verify` (backend):** needs JDK 21 + Maven + Maven Central. At test runtime it uses
  **in-memory H2** (`com.h2database:h2`, runtime scope; no `src/test/resources` override →
  default/`dev` profile), **memory** object storage, **memory** rate limiter, and the **mock**
  payment provider. **No external services are required** for this suite — but it is blocked
  here by the missing toolchain + blocked Maven Central.
- **`flutter test`:** needs Flutter SDK + Dart + pub.dev. Pure Dart unit/widget tests; **no
  backend or DB** required. Blocked here (no SDK + pub.dev blocked).
- **PostgreSQL RLS / real-DB integration:** **not** part of `mvn verify` — there are **no
  Testcontainers/`@Container`/`jdbc:postgresql`** references in `backend/src/test`. RLS
  parity is proven **in CI** against real `pgvector/pgvector:pg16`. Not runnable here.

## 7. Baseline checks I can actually run here
- Project builds/tests: **none executed** (blocked, §3/§5).
- Runnable: `git` and `python3`-based **static/structural** checks (e.g., package↔path
  consistency, JSON well-formedness, grep sweeps) — used previously with real results.

## 8. Exact commands to verify in a proper environment (Windows)
```powershell
# Backend: compile + full suite (H2 in-memory; no external services needed)
cd backend
mvn clean verify

# Mobile: analyzer + unit/widget tests (no backend needed)
cd ..\mobile
flutter pub get
flutter analyze
flutter test

# Real-DB / RLS parity (as CI does): needs Docker + Postgres(pgvector) + Redis
docker compose -f infra/docker-compose.yml up -d
# then boot the staging profile against jdbc:postgresql://localhost:5432/jotheca and run
# the cross-tenant RLS assertions from .github/workflows/ci.yml
```

## 9. Blocked / cannot-do list (explicit)
Run backend build/tests · run Flutter analyze/tests · start or connect to PostgreSQL, MinIO,
Redis, Mailpit · execute RLS integration tests · install any toolchain (network-blocked).
Consequently: **all runtime behavior is "IMPLEMENTED, NOT VERIFIED (runtime)" unless proven by
static evidence**; I will never present a build/test as passing without executing it.
