# Run LibraNet Locally — Everything You Need

Two components: **backend** (Java 21 / Spring Boot 3.5) and **client** (Flutter — mobile + web).
The backend also serves a zero-install **web mirror** of the app UI at `/`, so you can try
everything with just the backend running.

---

## 1. Prerequisites

| Tool | Version | Required for |
|---|---|---|
| JDK (Temurin) | 21 | backend |
| Maven | 3.9+ | backend build (no wrapper shipped) |
| Git | any | clone |
| Flutter SDK | 3.24+ (Dart `^3.5.0`) | mobile/web client |
| Docker Desktop | recent | **staging stack only** (Postgres+pgvector, Redis, MinIO, mailpit) |

No database install is needed for local dev — the `dev` profile runs **H2 in-memory
(PostgreSQL compatibility mode)**; Flyway applies V1–V19 and seed data on boot.

## 2. Backend (5 minutes)

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# — or —
mvn -DskipTests package
java -jar target/libranet-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

- App + web UI: **http://localhost:8080/**
- Health: `http://localhost:8080/actuator/health` → `{"status":"UP"}`
- Mail (dev): SMTP stub on port 1025 (invites/receipts logged, nothing sent)
- Full test suite: `mvn verify` → expect **151/151 green**

## 3. Flutter client

```bash
cd mobile
flutter pub get
flutter create --platforms=windows,android,web .   # FIRST TIME ONLY — scaffolds the
                                                   # platform runners (repo ships lib/ only)
flutter run -d chrome     # web
flutter run               # connected device / desktop
```

The repo intentionally contains no `android/`, `web/`, or `windows/` folders — they're
generated boilerplate; `flutter create .` writes them on your machine without touching
`lib/` or `pubspec.yaml`. Build executables with `flutter build windows` (needs the
**Visual Studio "Desktop development with C++"** workload on Windows), `flutter build apk`,
or `flutter build web --release`.

`API_BASE_URL` defaults to `http://localhost:8080`. Override when needed:

```bash
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080   # Android emulator → host machine
flutter run --dart-define=API_BASE_URL=https://api.example.com
```

## 4. Seeded dev accounts

| Email | Password | Role / library |
|---|---|---|
| patron@libranet.app | `Patron12345!` | member — Accra Central (LIB-AC2026) |
| staff@libranet.app | `Library12345!` | librarian — Pentecost (LIB-PU2026), staff AI tools |
| libadmin@libranet.app | `Library12345!` | library admin — Pentecost |
| accraadmin@libranet.app | `Library12345!` | library admin — Accra Central |
| author@libranet.app | `Author12345!` | author portal (LIB-AW2026) |
| admin@libranet.app | `Admin12345!` | SUPER_ADMIN (platform admin; never does library ops) |

Invite code **ASH-2026** joins Ashesi University Library (LIB-AU2026).

**Staff accounts** unlock: AI assistant, demand insights, usage/quota chips,
circulation desk (checkout/return via patron membership QR).

## 5. Try it in 3 minutes

1. Sign in as `patron@libranet.app` → Home shows the Accra membership card + QR.
2. **Discover** → search libraries, join Ashesi with `ASH-2026`.
3. **Catalog** → search the shelves (fuzzy/trigram search behind it).
4. **✨ AI discovery** → ask "something about Ghanaian history", view *For you*
   recommendations, ask the assistant "how do I renew?".
5. Sign out, sign in as `staff@libranet.app` → demand insights + AI usage appear.

## 6. Staging stack (Docker, production-like)

```bash
cp infra/.env.staging.example infra/.env   # fill in secrets
docker compose -f infra/docker-compose.yml --env-file infra/.env up
```

Detailed procedure, migration smoke assertions, and rollback:
[`docs/ops/STAGING-DEPLOY.md`](ops/STAGING-DEPLOY.md). Runbooks RB-01…08:
[`docs/ops/RUNBOOKS.md`](ops/RUNBOOKS.md). Backup/restore drills:
`scripts/backup.sh`, `scripts/restore.sh`.

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `mvn: command not found` | install Maven 3.9+, or `source scripts/bootstrap-tools.sh` (sandbox) |
| Port 8080 busy | `--server.port=8090` and `--dart-define=API_BASE_URL=http://localhost:8090` |
| Flutter app can't reach API from Android emulator | use `10.0.2.2`, not `localhost` |
| Fresh data | restart backend — H2 is in-memory, every boot reseeds |
| Tests fail | never commit on red; run `mvn verify`, see `docs/ARCHITECTURE-AND-ROADMAP.md` testing notes |
