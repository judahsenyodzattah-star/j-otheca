# J’otheca — Every Tool Involved & Its Commands

Complete inventory: what each piece of software is, why it's in the project, and the
exact commands this repository uses with it.

Legend: **[REQUIRED]** install to run the app · **[OPTIONAL]** install for specific paths
· **[EMBEDDED]** comes with the project, nothing to install.

---

## 1. Core toolchain

### Java 21 (Eclipse Temurin JDK) — [REQUIRED]
Backend language + runtime. Compiles and runs the Spring Boot app.

```bash
javac -version                 # verify install (21.x)
java -jar target/jotheca-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
java -jar target/jotheca-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=staging
java -jar ... --server.port=8090          # override port when 8080 is busy
```

### Maven 3.9+ — [REQUIRED]
Build, dependencies, tests. Downloads every Java library automatically (that's why
`.m2` never travels with the project).

```bash
mvn -DskipTests package        # build the jar (fast, no tests)
mvn verify                     # build + ALL 151 tests — the merge gate
mvn test                       # tests only
mvn test -Dtest=TenantIsolationTest       # one suite
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # run without packaging
mvn -q -B dependency:go-offline          # pre-fetch deps (used in the Dockerfile)
```

### Git — [REQUIRED]
Version control. `.gitignore` in the project root keeps generated junk out.

```bash
git init && git add . && git commit -m "J’otheca import"   # first local repo
git status / git log --oneline
```

### Sandbox bootstrap (Linux helper shipped in repo) — [OPTIONAL]
`scripts/bootstrap-tools.sh` — downloads portable JDK 21 + Maven 3.9.9 into
`~/.cache/tools` when you can't install system-wide:

```bash
source scripts/bootstrap-tools.sh     # afterwards mvn/java are on PATH
```

---

## 2. Client toolchain

### Flutter SDK 3.24+ (bundles Dart) — [OPTIONAL]
Builds the mobile + web client in `mobile/`. Not needed if you just use the
backend's built-in web UI at `http://localhost:8080`.

```bash
flutter doctor                          # check install & device toolchain
cd mobile
flutter pub get                         # fetch Dart dependencies
flutter analyze --no-fatal-infos        # lints (CI gate)
flutter test                            # widget/unit tests (CI gate)
flutter run -d chrome                   # run as web app
flutter run                             # run on connected device/emulator
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080   # Android emulator → host
flutter build web --release             # production web bundle (CI gate)
```

---

## 3. Containers — the staging/production stack

### Docker Engine / Docker Desktop + Compose v2 — [OPTIONAL]
Runs the real infrastructure. Also how you'd ship to a server.

```bash
cd infra
cp .env.staging.example .env            # then edit secrets
docker compose up -d                    # start postgres+redis+minio+mailpit
docker compose --profile full up -d     # ALSO run the backend as a container
docker compose ps                       # status
docker compose logs -f backend          # tail logs
docker compose down                     # stop
docker compose down -v                  # stop AND delete data volumes (fresh reset)
```

The images it pulls (nothing to build yourself except the backend image):

| Image | Purpose |
|---|---|
| `pgvector/pgvector:pg16` | PostgreSQL 16 + pgvector (vector search, RLS, trigram) |
| `redis:7-alpine` | cache / rate limits / dedup |
| `minio/minio:latest` | S3-compatible cover & file storage |
| `axllent/mailpit:latest` | email catcher with web inbox (invites, receipts) |

The backend image is built from `backend/Dockerfile` (multi-stage: Maven build →
JRE-21 runtime, non-root user). **Flyway** ([EMBEDDED] — runs inside the app on every
boot) applies migrations V1–V19 automatically against whichever DB it connects to.

---

## 4. Databases & dev stand-ins

### PostgreSQL 16 — [EMBEDDED via Docker] (staging) 
You never install it by hand; compose pulls it. Client tools for ops work:

```bash
psql "$DATABASE_URL" -c "SELECT 1"                          # connectivity
pg_dump  --format=custom --no-owner --dbname="$DATABASE_URL" --file=db.dump   # used by scripts/backup.sh
pg_restore --no-owner --dbname="$TARGET_URL" db.dump                         # used by scripts/restore.sh
```

### H2 — [EMBEDDED] (dev profile)
In-memory, PostgreSQL-compatibility mode. Zero install, reseeds on every boot. This is
why the dev quickstart needs no database.

### Redis / MinIO / mailpit stubs — [EMBEDDED] (dev profile)
In-process replacements so dev mode runs with literally nothing but Java + Maven.

---

## 5. Testing

| Tool | Type | Command |
|---|---|---|
| JUnit 5 + Spring Boot Test | [EMBEDDED] | `mvn verify` / `mvn test` (151 tests, 16 suites) |
| flutter_test | [EMBEDDED] | `flutter test` |
| curl | [REQUIRED on any OS] | API smoke tests — see scripts |
| GitHub Actions | hosted CI | `.github/workflows/ci.yml` runs `mvn -B verify`, flutter analyze/test/build, and a full staging migration smoke (real PG, asserts RLS policies, HNSW index, 4 trigram GIN indexes) on every push |

---

## 6. Ops scripts shipped in the repo

```bash
scripts/load-test.sh            # curl-based perf baseline vs budgets (BASE_URL=… ./scripts/load-test.sh)
scripts/backup.sh               # pg_dump custom-format DB backup + MinIO objects
scripts/restore.sh              # drop/recreate DB, pg_restore, post-checks (needs psql/pg_restore)
scripts/demo/demo-p9.sh         # scripted live demo: discovery/join flow (BASE_URL parameterised)
scripts/demo/demo-p10.sh        # scripted demo: AI features deep-dive
scripts/demo/demo-p11.sh        # scripted demo: hardening/ops features
```

## 7. Docs pipeline

### Python 3 + python-docx — [OPTIONAL]
Regenerates `.docx` from the Markdown masters in the academic pack.

```bash
pip install python-docx          # one time
python3 docs/academic/bin/to_docx.py docs/academic/01-proposal/PROJECT-PROPOSAL.md
python3 docs/academic/bin/to_docx.py docs/academic/04-final-report/DISSERTATION.md
```

### zip / tar — [REQUIRED on any OS]
Packaging the project for hand-in/sharing:

```bash
zip -qr jotheca-complete.zip jotheca -x "*/target/*" "*/build/*" "*/.dart_tool/*"
```

---

## 8. Minimum installs per scenario

| Scenario | Install |
|---|---|
| Run the app & web UI | JDK 21, Maven |
| + Flutter app | Flutter SDK |
| + production-like staging | Docker Desktop |
| + rebuild .docx deliverables | Python 3, `pip install python-docx` |
| + backup/restore drills | `postgresql-client` (psql, pg_dump, pg_restore) |
