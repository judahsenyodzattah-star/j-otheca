# Staging deploy runbook

Goal: the full stack on real Postgres (pgvector) + Redis + MinIO + SMTP, profile `staging`.
Prereqs: Docker host, DNS names, this repo, ~30 minutes.

## 1. Provision

| Component | Source | Notes |
| --- | --- | --- |
| Postgres 16 + pgvector | `pgvector/pgvector:pg16` | RLS + HNSW + trigram all live here |
| Redis 7 | `redis:7-alpine` | distributed rate limiting (fail-open by design, RB-03) |
| MinIO | `minio/minio:latest` | media blobs; bucket auto-created by the app |
| SMTP | mailpit (compose) for staging; real provider for prod | verification/notification emails |
| API | `backend/Dockerfile` | compressed slim JRE 21 image, non-root user |

## 2. Configure

```bash
cp infra/.env.staging.example infra/.env   # then edit: strong passwords, >=48-char APP_JWT_SECRET,
                                            # real PUBLIC_BASE_URL + CORS_ORIGINS
```

The `staging` profile (application-staging.yml) pins: Redis rate limiting, MinIO storage,
`pgvector` AI search backend, Redis health indicator on, Swagger off unless `SWAGGER_ENABLED=true`.
Secrets have **no defaults** — compose refuses to start with `${VAR:?}` errors until `.env` is complete.

## 3. Boot

```bash
cd infra
docker compose up -d postgres redis minio mailpit   # infra first
docker compose --profile full up -d backend          # builds + starts the API (runs Flyway V1–V19)
docker compose logs -f backend                       # wait for "Started LibraNetApplication"
```

## 4. Verify (the Day-1 gate)

1. **Health**: `curl -sf $HOST/actuator/health` → UP (Redis included in staging).
2. **Migrations on real PG** — same checks CI runs (`staging-migration-smoke` job):
   ```sql
   SELECT COUNT(*) FROM pg_indexes WHERE indexname='idx_book_embeddings_hnsw';   -- 1
   SELECT COUNT(*) FROM pg_indexes WHERE indexname LIKE '%_trgm';                -- 4
   SELECT COUNT(*) FROM pg_policies WHERE policyname LIKE '%_tenant_isolation';  -- >= 25
   ```
3. **Smoke**: `BASE_URL=https://staging.libranet.app bash demo-p11.sh` (cache headers,
   OpenAPI envelope, health) — must pass 6/6.
4. **Backups armed**: schedule `scripts/backup.sh` nightly (cron example below), then run the
   **P11 restore drill immediately** against a fresh `TARGET_DB` — sign off in
   `docs/ops/BACKUP-AND-RESTORE.md`.
   ```cron
   0 3 * * * DATABASE_URL=... MINIO_DATA_DIR=... BACKUP_DIR=/backups /opt/libranet/scripts/backup.sh >> /var/log/libranet-backup.log 2>&1
   ```
5. **TLS**: terminate at the edge (Caddy/ALB); the API stays plain HTTP inside the network.
   Update `PUBLIC_BASE_URL`/`CORS_ORIGINS` to the https origins at the same time.

## 5. Hand-over

- Production go/no-go: walk `docs/RELEASE-CHECKLIST.md`; record evidence in a new
  `docs/RELEASE-VERIFICATION-<date>.md` (template: the 2026-08-11 report).
- Rollback: previous image tag + point-in-time recovery from WAL (RPO ≤ 15 min); the release
  gate requires a restore drill *after* every destructive migration (none so far — migrations
  are extend-only by the change-control rule).
