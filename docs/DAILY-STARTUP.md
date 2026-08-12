# LibraNet — Everyday Startup Card

The 30-second version. Full tool inventory: `TOOLS-AND-COMMANDS.md` · First-time setup: `RUN-LOCALLY.md`.

---

## A. Normal dev day (your laptop)

```bash
cd libranet/backend
java -jar target/libranet-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

Then open **http://localhost:8080** → sign in `patron@libranet.app` / `Patron12345!`.
Stop with `Ctrl+C`. No database or service to start — dev mode is fully self-contained
(H2 in-memory, so data **reseeds on every restart** — that's normal).

**If the jar isn't built yet or you changed code:**

```bash
mvn -DskipTests package        # rebuild, then run the java -jar line again
```

**Before committing / handing in:**

```bash
mvn verify                     # all 120 tests must stay green
```

## B. Dev day + Flutter app (optional)

Terminal 1 → backend (block A). Terminal 2:

```bash
cd libranet/mobile
flutter run -d chrome                              # web
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080   # Android emulator
```

## C. Production-like day (staging, optional)

```bash
cd libranet/infra && docker compose up -d          # postgres+redis+minio+mailpit
cd ../backend
java -jar target/libranet-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=staging
# mail inbox → http://localhost:8025   ·   when done: docker compose down
```

## C2. Docker-first day (everything containerized — one command, like the old prototype)

```bash
cd libranet/infra
docker compose --profile full up -d                # postgres+redis+minio+mailpit+backend
# → http://localhost:8080 (app) · http://localhost:8025 (mailpit)
docker compose logs -f backend                     # watch the API boot/migrate
docker compose down                                # end of day (data persists in volumes)
docker compose down -v                             # also wipe volumes → fresh reseed next boot
docker compose --profile full build backend        # rebuild image after backend code changes
```

Requires `infra/.env` filled in once (`cp .env.staging.example .env`).
Data lives in named volumes (`pgdata`, `miniodata`, `redisdata`) — never in repo folders.

## D. Fresh session in this sandbox (Arena)

Build outputs don't persist between sandbox sessions, so each new session:

```bash
source libranet/scripts/bootstrap-tools.sh
cd libranet/backend && mvn -q -DskipTests package
export JAVA_HOME=/home/user/.cache/tools/jdk21
$JAVA_HOME/bin/java -jar target/libranet-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

---

### The everyday login sheet (each role opens a different console)

| Who | Email / password | What you get |
|---|---|---|
| Member demo | patron@libranet.app / `Patron12345!` | Home card+QR, Discover/join, Catalog, **My books**, AI |
| Staff demo | staff@libranet.app / `Library12345!` | all of the above **+ 🛠 Circulation desk** + staff AI |
| Library admin | accraadmin@libranet.app / `Library12345!` | all of the above **+ 🧭 Manage** (members, catalog, policy) |
| Platform admin | admin@libranet.app / `Admin12345!` | **🏛 Platform console only** (libraries, onboarding, plans, payments) — no tenant ops by design |

Join code for the Discover flow: **ASH-2026**.

### One-time-per-machine checklist (already done after first run)

1. `mvn -DskipTests package` → creates the jar → `.m2` cache filled, subsequent builds fast
2. `cd mobile && flutter pub get && flutter create --platforms=windows,android,web .`
   → only if using the Flutter client (repo ships no platform runners on purpose; this scaffolds them)
3. `cp infra/.env.staging.example infra/.env` → only if using staging
