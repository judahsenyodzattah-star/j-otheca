# Backup & restore (Phase 11)

## What must survive a disaster

1. **Postgres `libranet` database** — every table is created by Flyway migrations only
   (V1…V19). Schema is reproducible; **data** is the crown jewel.
2. **Object storage blobs** — media bytes under keys `t/{tenantId}/…`, referenced by
   `media_objects`. DB and blobs must come from the same backup window (backup script
   snapshots blobs immediately after the dump).

## Policy

| | |
| --- | --- |
| Full DB dump | nightly 03:00 UTC, `pg_dump --format=custom` |
| WAL archiving | continuous → **RPO ≤ 15 minutes** |
| Blob tar | nightly, immediately after the DB dump |
| Retention | 30 nightly + 12 monthly |
| Restore target | **RTO ≤ 1 hour** (drill below is the proof path) |

Scripts: `scripts/backup.sh` (env: `DATABASE_URL`, `MINIO_DATA_DIR`, `BACKUP_DIR`) and
`scripts/restore.sh` (env: `DATABASE_URL` admin DSN, `TARGET_DB`, `DUMP`, optional
`BLOBS`/`MINIO_DATA_DIR`).

## Quarterly restore drill (sign off an entry each quarter)

1. Pick the latest nightly pair (`libranet-db-*.dump`, `libranet-blobs-*.tar.gz`).
2. Run `scripts/restore.sh` with a **fresh** `TARGET_DB` (e.g. `libranet_restore_$(date +%s)`)
   — the script *only* drops the target you name, never implicitly the live DB.
3. The script's smoke block must print:
   - non-zero counts for `libraries`, `memberships`, `books`, `audit_logs`;
   - the seed tenant (`LIB-PU2026` etc. in shared envs);
   - `orphan_loans = 0` (referential integrity spot check).
4. Point a staging app instance at the restored DB + unpacked blobs, boot it, and verify:
   `/actuator/health` UP, login, one catalog search, one media byte fetch.
5. Record: date, dump name, restore duration, verifier initials in the ops log.

## Failure modes covered

- **Corrupt dump** → `pg_restore --list` fails in `backup.sh` (nightly alarm, backup is
  failed *when taken*, not when needed).
- **Partial restore** → `ON_ERROR_STOP=1`; the half-restored target is disposable.
- **Blob/DB skew** → media uploads reject when the quota ledger disagrees with actual
  usage (`storage_usage` is append-only; reconcile via the admin dashboard's storage bar).
